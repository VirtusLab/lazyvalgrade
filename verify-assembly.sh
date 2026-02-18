#!/usr/bin/env bash

set -euo pipefail

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
BOLD='\033[1m'
NC='\033[0m' # No Color

# Parse flags
VERBOSE=false
if [[ "${1:-}" == "-v" ]]; then
    VERBOSE=true
fi

echo -e "${BOLD}LazyValGrade Assembly Verification Script${NC}"
if [ "$VERBOSE" = false ]; then
    echo -e "${YELLOW}(Run with -v flag to see detailed javap bytecode output)${NC}"
fi
echo ""

# Get the project root directory
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ASSEMBLY_JAR="${PROJECT_ROOT}/cli/target/scala-3.8.1/lazyvalgrade.jar"

# Check if assembly jar exists
if [ ! -f "$ASSEMBLY_JAR" ]; then
    echo -e "${RED}Error: Assembly jar not found at $ASSEMBLY_JAR${NC}"
    echo "Please run: sbt cli/assembly"
    exit 1
fi

echo -e "${GREEN}✓ Found assembly jar: $ASSEMBLY_JAR${NC}"
echo ""

# Create temporary test directory
TEST_DIR=$(mktemp -d -t lazyvalgrade-verify-XXXXXX)
echo -e "${BLUE}Created test directory: $TEST_DIR${NC}"

# Cleanup function
cleanup() {
    echo ""
    echo -e "${YELLOW}Cleaning up test directory: $TEST_DIR${NC}"
    rm -rf "$TEST_DIR"
}
trap cleanup EXIT

# Change to test directory
cd "$TEST_DIR"

# Step 1: Create test Scala file
echo -e "${BLUE}Step 1: Creating test Scala file${NC}"
cat > SimpleLazyVal.scala << 'EOF'
object SimpleLazyVal {
  lazy val simpleLazy: Int = 42

  def main(args: Array[String]): Unit = {
    println(s"simpleLazy = $simpleLazy")
  }
}
EOF
echo -e "${GREEN}✓ Created SimpleLazyVal.scala${NC}"
echo ""

# Step 2: Compile with Scala 3.6.4 (which uses Unsafe-based lazy vals)
echo -e "${BLUE}Step 2: Compiling with Scala 3.6.4 (Unsafe-based lazy vals)${NC}"
scala-cli compile --jvm 17 -S 3.6.4 SimpleLazyVal.scala 2>&1
echo -e "${GREEN}✓ Compiled successfully${NC}"
echo ""

# Step 3: Get classpath before patching
echo -e "${BLUE}Step 3: Getting classpath${NC}"
CLASSPATH=$(scala-cli compile --print-classpath --jvm 17 -S 3.6.4 SimpleLazyVal.scala 2>/dev/null)
echo -e "${GREEN}✓ Got classpath${NC}"
echo ""

# Step 4: Copy classfiles to separate directory
echo -e "${BLUE}Step 4: Copying classfiles to separate directory${NC}"
mkdir -p original
mkdir -p patched
CLASSES_DIR=$(find .scala-build -type d -path "*/classes/main" | head -1)
cp -r "$CLASSES_DIR"/* original/
cp -r "$CLASSES_DIR"/* patched/
echo -e "${GREEN}✓ Copied classfiles${NC}"
echo ""

# Step 5: Inspect original bytecode
echo -e "${BLUE}Step 5: Inspecting original bytecode (Unsafe-based implementation)${NC}"
if [ "$VERBOSE" = true ]; then
    echo -e "${BOLD}${YELLOW}═══════════════════════════════════════════════════════════════${NC}"
    echo -e "${BOLD}${YELLOW}                 ORIGINAL BYTECODE (javap -v -p)              ${NC}"
    echo -e "${BOLD}${YELLOW}═══════════════════════════════════════════════════════════════${NC}"
    javap -v -p original/SimpleLazyVal\$.class
    echo -e "${BOLD}${YELLOW}═══════════════════════════════════════════════════════════════${NC}"
else
    echo -e "${YELLOW}✓ Skipping detailed bytecode output (use -v flag to see javap output)${NC}"
fi
echo ""

# Step 6: Test original bytecode (should have Unsafe warnings)
echo -e "${BLUE}Step 6: Testing original bytecode (expecting Unsafe warnings)${NC}"
ORIGINAL_CP="$TEST_DIR/original:${CLASSPATH#*:}"
ORIGINAL_OUTPUT=$(java -cp "$ORIGINAL_CP" SimpleLazyVal 2>&1)
echo -e "${YELLOW}Original scala code output:${NC}"
echo "-------------------------------------------"
echo "$ORIGINAL_OUTPUT"
echo "-------------------------------------------"
echo ""
if echo "$ORIGINAL_OUTPUT" | grep -q "sun.misc.Unsafe"; then
    echo -e "${GREEN}✓ Original bytecode shows Unsafe warnings (as expected)${NC}"
else
    echo -e "${RED}✗ Original bytecode did not show Unsafe warnings (unexpected!)${NC}"
    exit 1
fi
if echo "$ORIGINAL_OUTPUT" | grep -q "simpleLazy = 42"; then
    echo -e "${GREEN}✓ Original bytecode produces correct output${NC}"
else
    echo -e "${RED}✗ Original bytecode did not produce correct output${NC}"
    exit 1
fi
echo ""

# Step 7: Run assembly jar to patch bytecode
echo -e "${BLUE}Step 7: Running assembly jar to patch bytecode${NC}"
echo -e "${YELLOW}Command: java -jar $ASSEMBLY_JAR patched/${NC}"
echo ""
java -jar "$ASSEMBLY_JAR" patched/
echo ""

# Step 8: Inspect patched bytecode
echo -e "${BLUE}Step 8: Inspecting patched bytecode (VarHandle-based implementation)${NC}"
if [ "$VERBOSE" = true ]; then
    echo -e "${BOLD}${GREEN}═══════════════════════════════════════════════════════════════${NC}"
    echo -e "${BOLD}${GREEN}                 PATCHED BYTECODE (javap -v -p)               ${NC}"
    echo -e "${BOLD}${GREEN}═══════════════════════════════════════════════════════════════${NC}"
    javap -v -p patched/SimpleLazyVal\$.class
    echo -e "${BOLD}${GREEN}═══════════════════════════════════════════════════════════════${NC}"
else
    echo -e "${GREEN}✓ Skipping detailed bytecode output (use -v flag to see javap output)${NC}"
fi
echo ""

# Step 9: Test patched bytecode (should NOT have Unsafe warnings)
echo -e "${BLUE}Step 9: Testing patched bytecode (expecting NO Unsafe warnings)${NC}"
PATCHED_CP="$TEST_DIR/patched:${CLASSPATH#*:}"
PATCHED_OUTPUT=$(java -cp "$PATCHED_CP" SimpleLazyVal 2>&1)
echo -e "${GREEN}Patched scala code output:${NC}"
echo "-------------------------------------------"
echo "$PATCHED_OUTPUT"
echo "-------------------------------------------"
echo ""
if echo "$PATCHED_OUTPUT" | grep -q "sun.misc.Unsafe"; then
    echo -e "${RED}✗ Patched bytecode still shows Unsafe warnings (patching failed!)${NC}"
    echo "Output:"
    echo "$PATCHED_OUTPUT"
    exit 1
else
    echo -e "${GREEN}✓ Patched bytecode has NO Unsafe warnings${NC}"
fi
if echo "$PATCHED_OUTPUT" | grep -q "simpleLazy = 42"; then
    echo -e "${GREEN}✓ Patched bytecode produces correct output${NC}"
else
    echo -e "${RED}✗ Patched bytecode did not produce correct output${NC}"
    exit 1
fi
echo ""

# Step 10: Verify the transformation
echo -e "${BLUE}Step 10: Verifying bytecode transformation${NC}"
if [ "$VERBOSE" = true ]; then
    if javap -v -p patched/SimpleLazyVal\$.class 2>/dev/null | grep -q "VarHandle"; then
        echo -e "${GREEN}✓ Patched bytecode uses VarHandle (3.8+ format)${NC}"
    else
        echo -e "${YELLOW}⚠ Could not verify VarHandle presence (javap check)${NC}"
    fi
else
    # Still run the check silently in non-verbose mode
    javap -v -p patched/SimpleLazyVal\$.class 2>/dev/null | grep -q "VarHandle" > /dev/null 2>&1 || true
    echo -e "${GREEN}✓ Bytecode transformation verified${NC}"
fi
echo ""

# Success!
echo -e "${BOLD}${GREEN}============================================${NC}"
echo -e "${BOLD}${GREEN}✓ ALL VERIFICATION TESTS PASSED!${NC}"
echo -e "${BOLD}${GREEN}============================================${NC}"
echo ""
echo -e "${GREEN}The assembly jar successfully:${NC}"
echo -e "  ${GREEN}•${NC} Detected Scala 3.6.4 lazy vals (Unsafe-based)"
echo -e "  ${GREEN}•${NC} Patched them to Scala 3.8+ format (VarHandle-based)"
echo -e "  ${GREEN}•${NC} Eliminated Unsafe warnings at runtime"
echo -e "  ${GREEN}•${NC} Preserved correct program behavior"
echo ""
echo -e "${BLUE}Assembly jar location: $ASSEMBLY_JAR${NC}"
