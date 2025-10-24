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

**Scala 3.0-3.2.x**: Had one implementation of lazy vals using `sun.misc.Unsafe`

**Scala 3.3-3.7.x**: Changed to a different implementation, still using `sun.misc.Unsafe`

**Scala 3.8+**: New implementation without `sun.misc.Unsafe`

LazyValgrade must handle transformations from both older implementation variants to the 3.8+ implementation.

## The Solution

LazyValgrade provides bytecode-level transformations using ASM to rewrite lazy val implementations from Scala 3.0-3.7.x to the Scala 3.8.0 implementation.

### Use Cases

1. **Java Agent for Testing**: A preinit class loading mutator that hotfixes classes on-the-fly, enabling test suites to run on JDK 26+ with dependencies compiled with older Scala versions.

2. **Batch Build Tool**: A standalone tool for build systems to mutate complete application classpaths under closed-world assumption when building application assemblies, producing JDK 26+ compatible artifacts.

### Why This Works

- Scala 3 maintains binary backwards compatibility across versions
- Lazy val implementations are compatible at the semantic level
- Only the bytecode implementation details differ
- ASM can perform surgical transformations of the specific patterns emitted by each Scala version

## Project Structure

- `core/` - Core bytecode transformation logic using ASM
- `experimental/` - Experimental features and research

## Status

This is a proof-of-concept project under active development.

## Goal

Enable complete Scala 3 compatibility with JDK 26+ through bytecode patching, ensuring the Scala ecosystem can migrate to newer JDK versions without requiring ecosystem-wide recompilation.
