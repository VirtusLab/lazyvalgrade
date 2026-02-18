# LazyValgrade

A bytecode rewriting tool to enable Scala 3.0-3.7.x compatibility with JDK 26+.

## The Problem

Scala 3.x has a forward compatibility issue with upcoming JDK versions that poses a significant problem for the entire Scala 3 ecosystem.

### Background

**Scala 2 Implementation**: Scala 2's lazy vals used synchronized blocks and monitors. This implementation was slower but relied on standard JVM primitives that will exist as long as the JVM supports synchronization - essentially forever.

**Scala 3.0-3.7.x Implementation**: Scala 3 rewrote lazy vals for better performance, using `sun.misc.Unsafe`. This decision was made under the regime of supporting Java 1.8. Newer features meant to serve as a replacement for `sun.misc.Unsafe` - `VarHandles` - were added in Java 9. This meant that to maintain support for 1.8 `VarHandles` could not have been utilized and `sun.misc.Unsafe` was the only way to go until the decision to drop support for Java 1.8 was made. This decision was blocked in turn by many factors, including, but not limited to, the fact that Scala 3 introduced a number of breaking features already and that making a change to the minimum required version of the primary runtime would make migration even more difficult. 

**JDK 26+**: `sun.misc.Unsafe` is being removed in JDK 26, scheduled for release in the near future. This means all Scala 3 code compiled with versions 3.0-3.7.x emits `lazy val` bytecode that **will not work on JDK 26+**.

**Scala 3.8 Solution**: The Scala core team has prepared a fix by rewriting the compiler's code emission for lazy vals in Scala 3.8.0, eliminating the dependency on `sun.misc.Unsafe`. Scala 3.8.0 bumps the minimal required JDK version to 17 at the same time.

### The Critical Gap

The problem is not just with new code - it's with the entire existing ecosystem:

1. **Binary Backwards Compatibility**: Scala 3 maintains binary backwards compatibility across all 3.x versions, including lazy val implementations between versions. This is normally a strength.

2. **The Runtime Crash Scenario**: You can have a dependency compiled with Scala 3.3.x LTS used in a Scala 3.8.0 project. The code compiles fine, but **crashes at runtime** when a lazy val from the old dependency is accessed on JDK 26+.

3. **Ecosystem-Wide Impact**: Every library, framework, and application compiled with Scala 3.0-3.7.x needs to be either:
   - Recompiled with Scala 3.8+, or
   - Patched at the bytecode level

Recompiling the entire ecosystem is impractical and time-consuming. This is where LazyValgrade comes in.

### Implementation Differences

**Scala 3.0.x-3.1.x**: Bitmap-based inline implementation using `sun.misc.Unsafe` (94 instructions per accessor)

**Scala 3.2.x**: Minor refinement of 3.0.x/3.1.x bitmap-based implementation (88 instructions, different reflection API)

**Scala 3.3.x-3.7.x**: Complete redesign to object-based implementation with separate lzyINIT methods, still using `sun.misc.Unsafe` (26 instruction accessor)

**Scala 3.8+**: New VarHandle-based implementation without `sun.misc.Unsafe`

LazyValgrade must handle transformations from three distinct implementation families to the 3.8+ implementation.

## The Solution

LazyValgrade provides bytecode-level transformations using ASM to rewrite lazy val implementations from Scala 3.0-3.7.x to the Scala 3.8.0 implementation.

### Use Cases

1. **Batch Build Tool** (available now): A standalone CLI tool for build systems to mutate complete application classpaths under closed-world assumption when building application assemblies, producing JDK 26+ compatible artifacts.

2. **Java Agent for Testing** (planned): A preinit class loading mutator that hotfixes classes on-the-fly, enabling test suites to run on JDK 26+ with dependencies compiled with older Scala versions.

### Why This Works

- Scala 3 maintains binary backwards compatibility across versions
- Lazy val implementations are compatible at the semantic level
- Only the bytecode implementation details differ
- ASM can perform surgical transformations of the specific patterns emitted by each Scala version

## Project Structure

- `core/` - Core bytecode analysis, detection, and transformation logic using ASM
- `cli/` - Command-line interface for batch patching of classfiles
- `tests/` - Test suite with fixtures covering all Scala 3.x lazy val variants
- `testops/` - Development tooling for compiling examples across Scala versions and inspecting bytecode

## Status

Alpha-quality software under active development. Core detection and patching works across all Scala 3.0-3.7.x lazy val implementation families. See [TODO.md](TODO.md) for planned work.

## Usage

### CLI (Batch Patching)

```bash
# Build the CLI
sbt cli/assembly

# Patch all classfiles in a directory (in-place)
java -jar cli/target/scala-3.8.1/lazyvalgrade.jar <directory>
```

The CLI recursively finds all `.class` files in the given directory, detects Scala 3.0-3.7.x lazy val implementations, and rewrites them to the 3.8+ VarHandle-based format.

## Goal

Enable complete Scala 3 compatibility with JDK 26+ through bytecode patching, ensuring the Scala ecosystem can migrate to newer JDK versions without requiring ecosystem-wide recompilation.
