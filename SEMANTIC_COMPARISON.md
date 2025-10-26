# Semantic Lazy Val Comparison

## Overview

The `SemanticLazyValComparator` provides version-agnostic comparison of lazy val implementations. It answers the question: **"Are the lazy val implementations identical?"**

This comparison ignores all bytecode differences except lazy val implementation patterns, making it ideal for:
- Comparing the same source compiled with different Scala versions
- Detecting when lazy val implementations have changed
- Verifying transformation correctness

## Key Behavior

### Identical Cases (returns `Identical`)

1. **Same source + Scala 3.3 vs 3.7**
   - Both use object-based implementation with Unsafe
   - Even if other bytecode differs (e.g., implicit resolution), lazy vals are identical
   - Result: ✓ **IDENTICAL**

2. **Same source + Scala 3.0 vs 3.1**
   - Both use bitmap-based inline implementation
   - Result: ✓ **IDENTICAL**

3. **No lazy vals in either class**
   - Trivially identical
   - Result: ✓ **IDENTICAL**

### Different Cases (returns `Different`)

1. **Same source + Scala 3.2 vs 3.3**
   - 3.2 uses bitmap-based inline
   - 3.3 uses object-based with lzyINIT
   - Result: ✗ **DIFFERENT**

2. **Same source + Scala 3.7 vs 3.8**
   - 3.7 uses Unsafe (objCAS)
   - 3.8 uses VarHandle (compareAndSet)
   - Result: ✗ **DIFFERENT**

3. **Different lazy vals defined**
   - One class has lazy vals that the other doesn't
   - Result: ✗ **DIFFERENT**

## Usage

```scala
import lazyvalgrade.classfile.{ClassfileParser, ClassInfo}
import lazyvalgrade.lazyval.{SemanticLazyValComparator, LazyValFormatter}

// Parse two classfiles (same source, different Scala versions)
val bytes1: Array[Byte] = ??? // From Scala 3.3
val bytes2: Array[Byte] = ??? // From Scala 3.7

val class1 = ClassfileParser.parseClassfile(bytes1).toOption.get
val class2 = ClassfileParser.parseClassfile(bytes2).toOption.get

// Compare semantically
val result = SemanticLazyValComparator.compare(class1, class2)

// Check if identical
if result.areIdentical then
  println("Lazy val implementations are identical!")
else
  println("Lazy val implementations differ")

// Format for display
println(LazyValFormatter.formatSemanticComparisonResult(result))
```

## API

### `SemanticLazyValComparisonResult`

Sealed trait with the following cases:

- **`Identical`**
  - All lazy val implementations match
  - `areIdentical: Boolean = true`

- **`Different(reasons: Seq[String])`**
  - Lazy vals differ with specific reasons
  - `areIdentical: Boolean = false`

- **`BothNoLazyVals`**
  - Neither class has lazy vals (trivially identical)
  - `areIdentical: Boolean = true`

- **`OnlyOneHasLazyVals(firstHas: Boolean, count: Int)`**
  - Only one class has lazy vals
  - `areIdentical: Boolean = false`

### `SemanticLazyValComparator`

```scala
def compare(class1: ClassInfo, class2: ClassInfo): SemanticLazyValComparisonResult
```

Compares lazy val implementations by:
1. Detecting lazy vals in both classes
2. Matching them by name (primary identity)
3. Extracting canonical pattern signatures
4. Comparing patterns semantically
5. Returning simple identical/different result

### Canonical Patterns

The comparator normalizes lazy vals into canonical patterns based on detected version:

1. **BitmapBased (3.0-3.2)**
   - Has offset field (OFFSET$_m_<N>)
   - Has bitmap field (<N>bitmap$<M>)
   - Storage type (primitive or object)
   - No init method (inline implementation)

2. **ObjectBasedUnsafe (3.3-3.7)**
   - Has offset field (OFFSET$_m_<N>)
   - Storage type (Object)
   - Has init method (lzyINIT)
   - Instruction counts for init and accessor

3. **ObjectBasedVarHandle (3.8+)**
   - Has VarHandle field (<name>$lzy<N>$lzyHandle)
   - Storage type (Object)
   - Has init method (lzyINIT)
   - Instruction counts for init and accessor

## Formatting

### Detailed Output

```scala
LazyValFormatter.formatSemanticComparisonResult(result)
```

Example outputs:

**Identical:**
```
✓ Lazy val implementations are IDENTICAL
```

**Different:**
```
✗ Lazy val implementations DIFFER (2 reason(s)):
  • Lazy val 'simpleLazy': different implementation versions (ObjectBasedUnsafe vs ObjectBasedVarHandle)
  • Lazy val 'anotherLazy': storage type differs (I vs Ljava/lang/Object;)
```

### Summary (one line)

```scala
LazyValFormatter.formatSemanticComparisonSummary(result)
```

Examples:
- `"Identical"`
- `"Identical (no lazy vals)"`
- `"Different (2 reasons)"`

## Comparison with ClassfileComparator

| Feature | ClassfileComparator | SemanticLazyValComparator |
|---------|-------------------|-------------------------|
| Scope | Entire class | Lazy vals only |
| Granularity | All fields/methods | Lazy val patterns |
| Version-aware | No | Yes |
| Use case | General diff | Cross-version lazy val comparison |
| Result | Detailed differences | Simple identical/different |

## Examples

### Example 1: Scala 3.3 vs 3.7 (Identical)

```scala
// Same source: lazy val x: Int = 42
val class33 = parseClassfile(compiledWith33)
val class37 = parseClassfile(compiledWith37)

val result = SemanticLazyValComparator.compare(class33, class37)
// result: Identical
// Both use ObjectBasedUnsafe pattern
```

### Example 2: Scala 3.7 vs 3.8 (Different)

```scala
// Same source: lazy val x: Int = 42
val class37 = parseClassfile(compiledWith37)
val class38 = parseClassfile(compiledWith38)

val result = SemanticLazyValComparator.compare(class37, class38)
// result: Different(Seq("Lazy val 'x': different implementation versions..."))
// 3.7 uses Unsafe, 3.8 uses VarHandle
```

### Example 3: Same Version, Different Sources (Different)

```scala
// class1: lazy val x: Int = 42
// class2: lazy val y: String = "hello"
val result = SemanticLazyValComparator.compare(class1, class2)
// result: Different(Seq("Lazy vals only in first: x", "Lazy vals only in second: y"))
```

## Integration Points

The semantic comparator integrates with:
- `LazyValDetector`: For detecting lazy vals in classes
- `LazyValFormatter`: For formatting results
- `ClassfileParser`: For parsing bytecode

It provides the foundation for:
- Bytecode transformation validation
- Cross-version compatibility testing
- Lazy val migration tooling
