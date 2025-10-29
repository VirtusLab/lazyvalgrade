# LazyValGrade CLI

Command-line tool for patching Scala 3.x lazy val bytecode to Scala 3.8+ format.

## Overview

This tool transforms Scala 3.3-3.7 lazy val implementations (Unsafe-based) to Scala 3.8+ format (VarHandle-based), eliminating deprecated `sun.misc.Unsafe` warnings.

## Building

Build the assembly jar (fat jar with all dependencies):

```bash
sbt cli/assembly
```

The jar will be created at: `cli/target/scala-3.7.3/lazyvalgrade.jar`

## Usage

```bash
java -jar lazyvalgrade.jar <directory>
```

The tool will:
- Recursively find all `.class` files in the directory
- Detect Scala version and lazy val patterns
- Transform Scala 3.3-3.7 lazy vals to 3.8+ format
- **Patch files in-place** (overwrites original files)
- Provide a summary report with color-coded status

## Example

```bash
# Build the assembly
sbt cli/assembly

# Patch all classfiles in a directory
java -jar cli/target/scala-3.7.3/lazyvalgrade.jar /path/to/classes
```

## Output

The tool provides color-coded status for each file:
- **✓ PATCHED** (green) - Successfully transformed
- **○ NOT APPLICABLE** (blue) - Already 3.8+ or no lazy vals needing patching
- **- SKIPPED** (light blue) - No lazy vals found
- **✗ FAILED** (red) - Error during patching

Summary statistics are displayed at the end.

## Supported Versions

- **✅ Scala 3.3-3.7**: Fully supported (Unsafe → VarHandle transformation)
- **❌ Scala 3.0-3.1**: Not yet implemented (will fail fast with error)
- **❌ Scala 3.2**: Not yet implemented (will fail fast with error)
- **○ Scala 3.8+**: Already in target format, no patching needed

## Error Handling

The tool **fails fast** on:
- Unsupported Scala versions (3.0-3.1, 3.2)
- Unknown Scala versions
- Mixed Scala versions in classfiles
- Patching failures

Exit codes:
- `0` - Success
- `1` - Failure

## Verification

Run the verification script to test the assembly:

```bash
./verify-assembly.sh
```

This script:
1. Compiles a test example with Scala 3.6.4 (Unsafe-based)
2. Copies classfiles to a separate directory
3. Runs the assembly jar to patch them
4. Verifies the patched bytecode:
   - ✓ No Unsafe warnings at runtime
   - ✓ Correct program output
   - ✓ VarHandle usage detected

## Technical Details

The transformation:
- **Fields**: `OFFSET$_m_N` (long) → `<name>$lzyN$lzyHandle` (VarHandle)
- **Static initializer**: Unsafe offset lookup → VarHandle.findVarHandle
- **lzyINIT methods**: `LazyVals$.objCAS` → `VarHandle.compareAndSet`
- **Inner classes**: Adds `MethodHandles$Lookup` reference

## Dependencies

The assembly includes:
- lazyvalgrade-core (bytecode parsing and patching)
- os-lib (file operations)
- fansi (colored console output)
- scribe (logging)
- ASM (bytecode manipulation)
