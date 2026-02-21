# LazyValGrade Agent Architecture: A Catalog of Weird Shit

This document describes the non-obvious design decisions, workarounds, and architectural quirks in the LazyValGrade java agent. If you're reading this, you're either maintaining this codebase or morbidly curious about what it takes to rewrite bytecode at class-load time inside a running JVM.

## Table of Contents

1. [The Self-Patching Build](#1-the-self-patching-build)
2. [Anti-Shading String Construction](#2-anti-shading-string-construction)
3. [ClassLoader Hierarchy Resolution Without Class Loading](#3-classloader-hierarchy-resolution-without-class-loading)
4. [Companion Pair Buffering](#4-companion-pair-buffering)
5. [The Never-Throw Rule (With One Exception)](#5-the-never-throw-rule-with-one-exception)
6. [Double-Attach Guard](#6-double-attach-guard)
7. [Fast-Path Field Scanning](#7-fast-path-field-scanning)
8. [getResourceAsStream: Pure I/O, Not Class Loading](#8-getresourceasstream-pure-io-not-class-loading)
9. [OFFSET$_m_N vs OFFSET$N: The Module Split](#9-offset_m_n-vs-offsetn-the-module-split)
10. [Bitmap Index Independence](#10-bitmap-index-independence)
11. [3.0-3.1 Computation Extraction Heuristics](#11-30-31-computation-extraction-heuristics)
12. [Complete Method Synthesis for 3.0-3.1](#12-complete-method-synthesis-for-30-31)
13. [Type-Aware Boxing/Unboxing for Primitives](#13-type-aware-boxingunboxing-for-primitives)
14. [Backward-Walking Bytecode Removal](#14-backward-walking-bytecode-removal)
15. [Double-Parsing of Classfiles](#15-double-parsing-of-classfiles)
16. [Total Shading of Everything](#16-total-shading-of-everything)
17. [Tests Force Full Agent Assembly](#17-tests-force-full-agent-assembly)
18. [Trace-Level Byte Dumping](#18-trace-level-byte-dumping)
19. [Eager Companion Object False Positives](#19-eager-companion-object-false-positives)

---

## 1. The Self-Patching Build

**Files:** `build.sbt` (lines 88-126)

The agent's own dependencies -- including the Scala standard library -- are compiled with Scala 3.x and themselves use Unsafe-based lazy vals. So the build system patches the agent's own dependencies before assembling the final JAR.

The pipeline:
1. Build the CLI jar (which contains the patcher)
2. Run the CLI patcher against each dependency JAR: `java -cp <cliJar + deps> lazyvalgrade.cli.Main <depJar>`
3. Swap the patched JARs into the agent's assembly classpath

This is the `processDeps` task. The agent literally patches itself with itself.

The `assembly / fullClasspath` override swaps in processed JARs while keeping non-JAR entries (compiled class directories from project dependencies like `core`) intact. Without this, the agent JAR would ship with Unsafe-based lazy vals in its own shaded Scala runtime, triggering the very warnings it exists to eliminate.

The `DEBUG_AGENT_ASSEMBLY` environment variable enables verbose logging of this process.

---

## 2. Anti-Shading String Construction

**Files:** `LazyValGradeTransformer.scala` (line 28), `BytecodePatcher.scala` (lines 50-69)

sbt-assembly shade rules rewrite all `scala.**` references to `lazyvalgrade.shaded.scala.**`. But the patcher needs to match and generate references to the *application's* unshaded `scala.runtime.LazyVals$`, not the agent's own shaded copy.

Two different workarounds are used, for two different reasons:

**In the transformer (agent):**
```scala
new StringBuilder("sca").append("la/runtime/").toString
```
`StringBuilder` forces runtime construction, defeating the shade rule's constant pool scanner.

**In BytecodePatcher (core):**
```scala
private val ScalaRuntimePrefix: String = Array("sca", "la/run", "time/").mkString
```
Simple string concatenation (`"sca" + "la/..."`) would get *constant-folded by the Scala compiler*, placing the full literal `"scala/runtime/"` into the constant pool where the shade rule would find and rewrite it. `Array.mkString` forces runtime evaluation because the compiler can't fold it.

Both techniques achieve the same goal but exist because the failure modes are different: one defeats the shade rule scanner, the other defeats the Scala compiler's constant folding that would expose the string to the shade rule scanner.

---

## 3. ClassLoader Hierarchy Resolution Without Class Loading

**File:** `ClassLoaderClassWriter.scala`

ASM's `COMPUTE_FRAMES` needs to find common superclasses at branch join points. The default `ClassWriter` uses `Class.forName()` which triggers class loading -- catastrophic in agent mode because it can load classes with unpatched bytes before the transformer gets a chance to patch them. It also risks recursive transforms (the transformer calls ASM which calls `Class.forName` which calls the transformer...).

The custom `ClassLoaderClassWriter` implements a three-tier resolution strategy:

1. **`getResourceAsStream` from the provided classloader** -- reads raw bytes, parses superclass with `ClassReader`, never triggers class loading
2. **`getResourceAsStream` from the system classloader** (if different) -- handles cases where the app classloader can't see platform/system classes
3. **`Class.forName` fallback** -- but ONLY for JDK types (`java/`, `javax/`, `jdk/`, `sun/`, `com/sun/`). Safe because JDK classes never contain Scala lazy vals and are already skipped by the agent's `skipPrefixes`, so no recursive transform risk.

The third tier was added to fix a VerifyError in the Scala compiler's `FileZipArchive` class. That class has a lazy val (triggering patching), and its `openZipFile()` method branches between `new JarFile(...)` and `new ZipFile(...)`. ASM needed to merge these types at the join point but couldn't find them via `getResourceAsStream` on Java 9+ (module system hides JDK classes from `getResourceAsStream` on arbitrary classloaders). Without the `Class.forName` fallback, ASM computed `Object` as the common superclass instead of `ZipFile`, producing an invalid stack map frame that the JVM verifier rejected.

The `isAssignableFrom` method reimplements the full interface-and-superclass check via BFS, all without loading any classes.

---

## 4. Companion Pair Buffering

**File:** `LazyValGradeTransformer.scala` (lines 33, 54-58, 126-136)

Companion pairs (`Foo$` and `Foo`) must be patched together because OFFSET fields can live in the companion class while the `lzyINIT` methods live in the companion object. But the JVM's `ClassFileTransformer` only provides one class at a time.

The solution uses a `ConcurrentHashMap` as a cross-load buffer:

1. When one side of a companion pair arrives, load the other side's bytes via `getResourceAsStream` (pure I/O, no class loading)
2. Patch both together using the group-based API
3. Return the current class's patched bytes immediately
4. Store the companion's patched bytes in the map via `put()`
5. When the companion eventually loads, `remove()` retrieves and consumes the buffered bytes -- the transformer returns them directly without re-patching

**Race condition:** Two threads loading `Foo` and `Foo$` simultaneously will both form a CompanionPair and patch independently. Both produce correct bytes. The JVM serializes class definition, so there's no correctness issue, just wasted work. The buffer entries are self-cleaning via `remove()`.

**Memory leak:** If a companion never loads, a stale entry remains in the map forever. This is acknowledged and considered negligible.

---

## 5. The Never-Throw Rule (With One Exception)

**File:** `LazyValGradeTransformer.scala` (lines 151-164)

A `ClassFileTransformer` must never throw -- returning `null` means "leave unchanged." The catch block catches `Throwable` and returns `null`.

**However**, there is one deliberate carve-out: `LazyValPatchingException` is re-thrown. The rationale: if patching detects an `Unknown` lazy val version, the class has broken Unsafe-based lazy vals that would cause `VerifyError` at runtime anyway. It's better to crash early with a diagnostic message (including a full dump of fields, methods, and per-lazy-val detection results) than to silently load broken bytecode and get an inscrutable error later.

---

## 6. Double-Attach Guard

**File:** `LazyValGradeAgent.scala` (lines 20-29)

An `AtomicInteger` detects if `premain` is called more than once, which happens if `-javaagent` is specified alongside `JAVA_TOOL_OPTIONS` containing the same agent. On the second call, it throws a `RuntimeException` to prevent duplicate transformers that would double-patch classfiles. The error message specifically tells the user to remove one of the two agent specifications.

---

## 7. Fast-Path Field Scanning

**File:** `LazyValGradeTransformer.scala` (lines 183-212)

Before doing any real work, the transformer runs a lightweight ASM pass with `SKIP_CODE | SKIP_DEBUG` that only visits field declarations. It short-circuits as soon as it finds either:
- A `$lzy` field (but NOT `$lzyHandle` -- that indicates 3.8+ format, already good)
- An `OFFSET$` field with descriptor `J` (long)

This avoids the cost of full parsing, companion loading, and group analysis for the vast majority of classes that have no lazy vals at all.

---

## 8. getResourceAsStream: Pure I/O, Not Class Loading

**File:** `LazyValGradeTransformer.scala` (lines 81-100)

The companion's bytes are loaded via `loader.getResourceAsStream(name + ".class")` rather than `Class.forName` or `loadClass`. This is explicitly noted as "pure I/O, no class definition" because `loadClass` would:
1. Trigger the transformer recursively
2. Potentially load the companion with unpatched bytes before we can patch it
3. Define the class, making it impossible to replace with patched bytes later

The `null` loader check (bootstrap classloader) is a separate guard from `skipPrefixes` -- bootstrap classes that somehow pass prefix filtering are still skipped because resource loading behaves differently on the bootstrap classloader.

---

## 9. OFFSET$_m_N vs OFFSET$N: The Module Split

**Files:** `BytecodePatcher.scala` (throughout), `LazyValDetector.scala`

The Scala compiler uses two OFFSET field naming patterns:
- **`OFFSET$_m_N`**: Offsets for the companion *module* (object)'s lazy vals, stored in the companion *class*
- **`OFFSET$N`**: Offsets for the class's own lazy vals

The `_m_` infix stands for "module." This split is why companion pairs must be patched together: the object's `lzyINIT` method uses `GETSTATIC CompanionClass.OFFSET$_m_0`, referencing a field in a different classfile.

**Critical indexing detail:** `OFFSET$_m_N` maps to the Nth lazy val *by position* (0-indexed), NOT by the lazy val's own index. All lazy vals in a class can have the same storage index (typically 1), so mapping must be purely positional. This is called out with `IMPORTANT` comments in four separate locations across the patcher.

---

## 10. Bitmap Index Independence

In Scala 3.0-3.2 bitmap-based lazy vals, the bitmap field index (e.g., `0bitmap$2`) does NOT correspond to the storage field index (e.g., `$lzy1`). The compiler assigns bitmap numbers independently. The detector must not match by index -- it uses the `<clinit>` offset mapping to correlate bitmap fields to storage fields.

---

## 11. 3.0-3.1 Computation Extraction Heuristics

**File:** `BytecodePatcher.scala` (lines 564-643)

Extracting the lazy val computation from 3.0-3.1 accessor methods is a heuristic bytecode-walking process:

1. Find the `CAS` call in the accessor
2. Find the `IFEQ` jump after it (the "CAS succeeded" branch)
3. Walk forward, collecting instructions until either:
   - A `xSTORE 5` instruction (storing the computed value in local slot 5) -- normal case
   - A `LabelNode` matching a try-catch block end label where the handler calls `setFlag` -- always-throwing computation case

The **always-throwing computation** edge case deserves special mention: the code handles lazy vals whose computation always throws an exception. It walks the accessor's `tryCatchBlocks` looking for handlers that call `LazyVals$.setFlag`, and uses the `end` label of that try-catch as the extraction boundary.

The `xSTORE 5` boundary is a hardcoded assumption about the Scala 3.0-3.1 compiler's register allocation. If the compiler ever changes its local variable layout, this extraction would silently produce incorrect bytecode. It's been stable across 3.0.x-3.1.x releases.

Label cloning: all `LabelNode`s in the extracted range are cloned into a `HashMap<LabelNode, LabelNode>` map, and `FrameNode`s are skipped (recomputed by `COMPUTE_FRAMES`).

---

## 12. Complete Method Synthesis for 3.0-3.1

**File:** `BytecodePatcher.scala` (lines 724-935)

For 3.0-3.1 patching, the entire `lzyINIT` method is synthesized from scratch to match the 3.8+ pattern. This is ~200 lines of hand-assembled bytecode including:

- A CAS `null -> Evaluating` loop
- Try-catch block for exception safety (must clean up `Evaluating` sentinel on failure)
- `Waiting.countDown()` for thread contention handling
- `NullValue$` sentinel for null-returning lazy vals

The method hardcodes `maxStack = 5` and `maxLocals = 6`. These are manually calculated based on the synthesized instruction flow, not computed. They must match exactly.

---

## 13. Type-Aware Boxing/Unboxing for Primitives

**File:** `BytecodePatcher.scala` (lines 1046-1092)

In 3.0-3.2, lazy vals for primitives have typed storage fields (e.g., descriptor `I` for `Int`). After patching to 3.8+ format, storage becomes `Object`, requiring boxing/unboxing via `scala.runtime.BoxesRunTime`.

The patcher maps each primitive descriptor to a `TypeInfo` record containing:
- The `INSTANCEOF` check type
- The correct return opcode (`IRETURN`, `DRETURN`, `FRETURN`, etc.)
- Boxing lambda (e.g., `BoxesRunTime.boxToInteger`)
- Unboxing lambda (e.g., `BoxesRunTime.unboxToInt`)

A trick: `BoxesRunTime.unboxToInt(null)` returns `0`, `unboxToBoolean(null)` returns `false`, etc. This is used to return default values for `NullValue$` sentinels without special-casing each primitive.

These `BoxesRunTime` references are NOT shade-protected because they point to the *application's* Scala runtime, not the agent's. The shade rules rewrite `scala.**` in the agent's own bytecode, but these strings are used to *generate* bytecode that runs in the application's classloader where `scala.runtime.BoxesRunTime` exists unshaded.

---

## 14. Backward-Walking Bytecode Removal

**File:** `BytecodePatcher.scala` (multiple locations)

Removing OFFSET initialization from `<clinit>` uses a backward-walking pattern from the `PUTSTATIC OFFSET$*` instruction. Starting from the PUTSTATIC, walk backwards through instructions until finding the `GETSTATIC LazyVals$.MODULE$` that starts the initialization sequence, then remove everything in between.

A safety limit (`removeCount < 10`) prevents infinite backward walking in unexpected bytecode. The loop is terminated via a sentinel value (`removeCount = 999`) rather than `break` (which doesn't exist in Scala).

This pattern is duplicated in at least four separate methods rather than extracted into a helper.

---

## 15. Double-Parsing of Classfiles

**File:** `ClassfileParser.scala` (lines 25-36)

`parseClassfile` creates two separate `ClassReader` instances and parses the same bytes twice:
1. `readClassNode(bytes)` -- builds the ASM tree for structured manipulation
2. `generateFullDump(bytes)` -- generates textual Textifier output for text-based method extraction

The textual dump is needed by `extractMethodBytecode`, which finds method boundaries by scanning for lines starting with `"  // access flags"`. This text-based approach exists alongside the structured ASM tree because some analysis (like the OFFSET mapping in `LazyValDetector`) was originally written against text and was never migrated.

---

## 16. Total Shading of Everything

**File:** `build.sbt` (lines 133-143)

The agent shades *all* of its dependencies:
- `scala.**` -- the entire Scala standard library
- `org.objectweb.asm.**` -- ASM bytecode framework
- `scribe.**` -- logging library
- `perfolation.**`, `moduload.**`, `sourcecode.**` -- scribe's transitive dependencies
- `com.lihaoyi.**`, `os.**`, `geny.**` -- os-lib and its dependencies

This is necessary because the agent runs in the application's JVM and cannot conflict with the application's own versions of these libraries. But it creates the anti-shading problems described in section 2, and is why the self-patching build (section 1) exists.

---

## 17. Tests Force Full Agent Assembly

**File:** `build.sbt` (lines 51-53)

Both `test` and `testOnly` depend on `agent / assembly`. Since assembly triggers `processDeps`, which builds the CLI jar and runs it against all dependency JARs, running *any* test triggers the full multi-stage build pipeline:

1. Compile core
2. Compile + assemble CLI
3. Run CLI patcher on each agent dependency JAR
4. Assemble agent JAR with patched deps + shade rules
5. Finally, run tests

This is why `sbt test` is slow and the CLAUDE.md instructions say to always use `SELECT_EXAMPLE` and `ONLY_SCALA_VERSIONS` filters.

---

## 18. Trace-Level Byte Dumping

**File:** `LazyValGradeTransformer.scala` (lines 167-180)

At trace log level (`-javaagent:agent.jar=trace`), the transformer dumps every patched class's bytes to `/tmp/lazyvalgrade-dump-<pid>-<name>.class`. The filename sanitizes `$` and `.` to `_`. These dumps happen even on success and are purely for post-mortem `javap` inspection.

---

## 19. Eager Companion Object False Positives

**File:** `LazyValDetector.scala` (lines 196-199)

Fields matching the `$lzy` name pattern but lacking all lazy val infrastructure (no OFFSET, no bitmap, no VarHandle, no init method, and not volatile) are silently skipped rather than raising an `Unknown` version error. These are "likely eager companion object references" -- fields that happen to have `$lzy` in their name but are not actually lazy vals. Without this filter, the hard-fail-on-unknown policy would crash the JVM on innocent classes.
