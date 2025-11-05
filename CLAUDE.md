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
# Run from sbt
sbt compileExamples

# Or run the assembly directly
sbt testops/assembly
java -jar testops/target/scala-3.7.3/lazyvalgrade-testops.jar
```

**What it does:**

1. Discovers all examples in `tests/src/test/resources/fixtures/examples/`
2. Compiles each example with all test Scala versions:
   - 3.0.2, 3.1.3, 3.2.2
   - 3.3.0, 3.3.6, 3.4.3, 3.5.2, 3.6.4, 3.7.3
   - 3.8.0-RC1-bin-20251026-5c51b7b-NIGHTLY
3. Generates javap disassembly (`.javap.txt`) for each compiled classfile
4. Outputs everything to `.out/` directory

**Output structure:**

```
.out/
  <example-name>/
    <scala-version>/
      *.class           # Compiled classfiles
      *.javap.txt       # Javap disassembly
      *.scala          # Source files (copied)
      .scala-build/    # scala-cli build artifacts
```

**Inspecting results:**

```bash
# List all examples
ls .out/

# View javap output for a specific version
cat .out/companion-object-lazy-val/3.3.0/Foo$.javap.txt

# Compare lazy val implementations across versions
grep -h "OFFSET\|bitmap\|lzyHandle" .out/simple-lazy-val/*/Main$.javap.txt

# Count generated files
find .out -name "*.javap.txt" | wc -l
```

**Use cases:**

- Debugging lazy val detection across Scala versions
- Comparing bytecode patterns between versions
- Verifying transformation correctness
- Understanding lazy val implementation changes

## Testing

```bash
# Run all tests
sbt test

# Run specific test suite
sbt "tests/testOnly lazyvalgrade.LazyValDetectionTests"

# Run specific test
sbt "tests/testOnly lazyvalgrade.LazyValDetectionTests -- *companion-object-lazy-val*"
```

## Building

```bash
# Compile all modules
sbt compile

# Build CLI assembly
sbt cli/assembly
# Output: cli/target/scala-3.7.3/lazyvalgrade.jar

# Build testops assembly
sbt testops/assembly
# Output: testops/target/scala-3.7.3/lazyvalgrade-testops.jar
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

### Known Issues

- Test failures can be intermittent (see previous session notes)
- Some race conditions in parallel compilation/detection
- VarHandle OFFSET field detection differs between standalone and companion cases

## Debugging Tips

1. Use `compileExamples` to generate fresh bytecode when behavior seems inconsistent
2. Compare javap outputs across versions to understand bytecode differences
3. Check `.out/` directory structure when tests fail to verify compilation succeeded
4. Use `javap -v -p` manually for deeper inspection of specific classfiles
