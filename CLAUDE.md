# Claude Code Development Guide

This document contains information for Claude Code (or other AI assistants) working on the lazyvalgrade project.

## Project Structure

- **core/** - Core bytecode analysis and transformation logic
- **cli/** - Command-line interface for running transformations
- **tests/** - Test suite with fixtures and unit tests
- **testops/** - Development tooling for debugging and testing

## Development Tools

### Compiling Test Examples

The `compileExamples` command compiles all test fixtures across multiple Scala versions and generates javap outputs for bytecode inspection.

**Usage:**

```bash
# Compile examples without patching
sbt compileExamples

# Compile examples and generate patched versions (3.3-3.7)
sbt compileExamplesWithPatching

# Run with example filtering
SELECT_EXAMPLE=simple-lazy-val sbt compileExamples
SELECT_EXAMPLE=simple-lazy-val,class-lazy-val sbt compileExamplesWithPatching

# Or run the assembly directly
sbt testops/assembly
java -jar testops/target/scala-3.8.1/lazyvalgrade-testops.jar
java -jar testops/target/scala-3.8.1/lazyvalgrade-testops.jar --patch
```

**What it does:**

1. Discovers all examples in `tests/src/test/resources/fixtures/examples/`
2. Compiles each example with all test Scala versions:
   - 3.0.2, 3.1.3, 3.2.2
   - 3.3.0, 3.3.6, 3.4.3, 3.5.2, 3.6.4, 3.7.3
   - 3.8.1
3. Generates javap disassembly (`.javap.txt`) for each compiled classfile
4. With `--patch` flag: Transforms Scala 3.3-3.7 classfiles to use VarHandle-based lazy vals (like 3.8+)
5. Outputs everything to `.out/` directory

**Output structure:**

```
.out/
  <example-name>/
    <scala-version>/
      *.class           # Compiled classfiles
      *.javap.txt       # Javap disassembly
      *.scala          # Source files (copied)
      .scala-build/    # scala-cli build artifacts
    patched/           # Only present when using --patch flag
      3.3.0/           # Patched versions (3.3-3.7 only)
        *.class        # Patched classfiles with VarHandle-based lazy vals
        *.javap.txt    # Javap disassembly of patched files
      3.3.6/
      3.4.3/
      3.5.2/
      3.6.4/
      3.7.3/
```

**Inspecting results:**

```bash
# List all examples
ls .out/

# View javap output for a specific version
cat .out/companion-object-lazy-val/3.3.0/Foo$.javap.txt

# View patched javap output
cat .out/companion-object-lazy-val/patched/3.3.0/Foo$.javap.txt

# Compare lazy val implementations across versions
grep -h "OFFSET\|bitmap\|lzyHandle" .out/simple-lazy-val/*/SimpleLazyVal$.javap.txt

# Compare original vs patched (OFFSET -> VarHandle)
diff .out/simple-lazy-val/3.3.0/SimpleLazyVal$.javap.txt .out/simple-lazy-val/patched/3.3.0/SimpleLazyVal$.javap.txt

# Count generated files
find .out -name "*.javap.txt" | wc -l
```

**Use cases:**

- Debugging lazy val detection across Scala versions
- Comparing bytecode patterns between versions
- Verifying transformation correctness
- Understanding lazy val implementation changes
- Testing bytecode patching by comparing original vs patched implementations
- Inspecting VarHandle vs Unsafe-based lazy val bytecode

## Testing

```bash
# Run all tests
sbt test

# Run specific test suite
sbt "tests/testOnly lazyvalgrade.LazyValDetectionTests"

# Run specific test
sbt "tests/testOnly lazyvalgrade.LazyValDetectionTests -- *companion-object-lazy-val*"
```

### Filtering Examples and Scala Versions

All test suites support filtering using environment variables:

#### SELECT_EXAMPLE - Filter by example name

```bash
# Run tests for a single example
SELECT_EXAMPLE=simple-lazy-val sbt test

# Run tests for multiple examples (comma-separated)
SELECT_EXAMPLE=simple-lazy-val,class-lazy-val sbt test

# Run specific test suite with filtering
SELECT_EXAMPLE=companion-object-lazy-val sbt "tests/testOnly lazyvalgrade.BytecodePatchingTests"

# Without SELECT_EXAMPLE, all examples are tested (default behavior)
sbt test
```

#### ONLY_SCALA_VERSIONS - Filter by Scala version

```bash
# Test only specific Scala versions
ONLY_SCALA_VERSIONS=3.1.3,3.3.0 sbt test

# Combine with example filtering for targeted testing
SELECT_EXAMPLE=simple-lazy-val ONLY_SCALA_VERSIONS=3.3.0,3.4.3 sbt test

# Test a problematic version in isolation
ONLY_SCALA_VERSIONS=3.3.0 sbt "tests/testOnly lazyvalgrade.LazyValDetectionTests"
```

#### INSPECT_BYTECODE - Enable bytecode inspection on test failures

When enabled, this mode automatically prints `javap -v -p` output for failing test cases,
showing the failed version plus adjacent versions for comparison.

```bash
# Enable bytecode inspection for all test failures
INSPECT_BYTECODE=true sbt test

# Combine all filters for precise debugging
INSPECT_BYTECODE=true SELECT_EXAMPLE=multiple-lazy-vals ONLY_SCALA_VERSIONS=3.1.3,3.3.0 sbt test

# Debug a specific test with full bytecode output
INSPECT_BYTECODE=1 SELECT_EXAMPLE=simple-lazy-val ONLY_SCALA_VERSIONS=3.3.0 \
  sbt "tests/testOnly lazyvalgrade.LazyValDetectionTests"
```

**INSPECT_BYTECODE accepts:** `true`, `1`, `yes` (case insensitive)

**Available examples:**
- `simple-lazy-val` - Basic lazy val in object
- `class-lazy-val` - Lazy val in class
- `companion-object-lazy-val` - Lazy val in companion object
- `companion-class-lazy-val` - Lazy val in companion class
- `multiple-lazy-vals` - Multiple lazy vals in single object
- `abstract-class-lazy-val` - Lazy val in abstract class
- `trait-class-lazy-val` - Lazy val in trait
- `no-lazy-val` - Control case with no lazy vals

**Use cases:**
- Faster iteration when working on specific examples
- Debugging issues in particular test cases
- CI optimization by parallelizing example tests

## Building

```bash
# Compile all modules
sbt compile

# Build CLI assembly
sbt cli/assembly
# Output: cli/target/scala-3.8.1/lazyvalgrade.jar

# Build testops assembly
sbt testops/assembly
# Output: testops/target/scala-3.8.1/lazyvalgrade-testops.jar
```

## Important Notes

### Lazy Val Detection

The project detects and transforms lazy val implementations across different Scala 3 versions:

- **3.0.x - 3.2.x**: Bitmap-based with typed storage fields
- **3.3.x - 3.7.x**: OFFSET-based with Object storage and objCAS
- **3.8.x+**: VarHandle-based with Object storage

### Test Fixtures

Test fixtures are located in `tests/src/test/resources/fixtures/examples/`:
- `simple-lazy-val/` - Basic lazy val in object
- `class-lazy-val/` - Lazy val in class
- `companion-object-lazy-val/` - Lazy val in companion object
- `companion-class-lazy-val/` - Lazy val in companion class
- `no-lazy-val/` - Control case with no lazy vals

Each fixture includes a `metadata.json` file describing expected lazy val patterns.

### Test Output Control

Test suites support a `quietTests` flag to control verbosity. All test suites are **quiet by default** (minimal output).

To enable verbose output for debugging, override in specific test suites:
```scala
override val quietTests: Boolean = false  // Enable verbose output
```

### Known Issues

- Test failures can be intermittent (see previous session notes)
- Some race conditions in parallel compilation/detection
- VarHandle OFFSET field detection differs between standalone and companion cases

## Debugging Tips

### Quick Debugging Workflow

When a test fails, use this workflow for maximum debugging velocity:

```bash
# 1. Enable bytecode inspection and narrow down to the failing case
INSPECT_BYTECODE=true SELECT_EXAMPLE=<failing-example> ONLY_SCALA_VERSIONS=<failing-version> sbt test

# 2. The test will automatically print javap output for the failed version and adjacent versions

# 3. For deeper analysis, compile the examples separately and inspect manually
SELECT_EXAMPLE=<example> sbt compileExamples
cat .out/<example>/<version>/<ClassName>.javap.txt

# 4. Compare bytecode across versions
diff .out/<example>/3.3.0/<Class>.javap.txt .out/<example>/3.4.3/<Class>.javap.txt
```

### General Tips

1. Use `compileExamples` to generate fresh bytecode when behavior seems inconsistent
2. Use `INSPECT_BYTECODE=true` to automatically see bytecode on test failures
3. Use `ONLY_SCALA_VERSIONS` to test specific problematic versions in isolation
4. Combine all three environment variables for pinpoint debugging
5. Check `.out/` directory structure when tests fail to verify compilation succeeded
6. Use `javap -v -p` manually for deeper inspection of specific classfiles

### Example Debugging Session

```bash
# Start with a broad test to identify failures
sbt test

# Test fails on multiple-lazy-vals with Scala 3.3.0
# Narrow down and inspect bytecode
INSPECT_BYTECODE=true SELECT_EXAMPLE=multiple-lazy-vals ONLY_SCALA_VERSIONS=3.1.3,3.3.0 sbt test

# The output will show javap for both versions, making it easy to spot differences
# in lazy val implementation (bitmap vs OFFSET-based)
```
