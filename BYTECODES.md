# Bytecode Transformation Patterns for Scala 3 Lazy Vals

## Overview

This document describes the bytecode-level differences between Scala 3.0-3.7.x (Unsafe-based) and Scala 3.8+ (VarHandle-based) implementations of lazy vals, and the transformation patterns required to convert the former to the latter.

## Version History

### Bytecode Drift Analysis

Using our bytecode drift detector on a simple lazy val example (`lazy val simpleLazy: Int = 42`), we observed the following drift in `Main$.class`:

```
Scala 3.0.2    - Size:   1731 bytes, SHA256: ac1e4db8cad63f23...
Scala 3.1.3    - Size:   1739 bytes, SHA256: 24494df804e7a774...
Scala 3.2.2    - Size:   1814 bytes, SHA256: a4d09d8473fc05c4...
Scala 3.3.0    - Size:   2403 bytes, SHA256: a610afab73e5bcd1...
Scala 3.3.1    - Size:   2403 bytes, SHA256: a610afab73e5bcd1...
Scala 3.3.2    - Size:   2403 bytes, SHA256: a610afab73e5bcd1...
Scala 3.3.3    - Size:   2403 bytes, SHA256: a610afab73e5bcd1...
Scala 3.3.4    - Size:   2403 bytes, SHA256: a610afab73e5bcd1...
Scala 3.3.5    - Size:   2403 bytes, SHA256: a610afab73e5bcd1...
Scala 3.3.6    - Size:   2403 bytes, SHA256: a610afab73e5bcd1...
Scala 3.4.3    - Size:   2403 bytes, SHA256: a610afab73e5bcd1...
Scala 3.5.2    - Size:   2403 bytes, SHA256: a610afab73e5bcd1...
Scala 3.6.4    - Size:   2403 bytes, SHA256: a610afab73e5bcd1...
Scala 3.7.3    - Size:   2403 bytes, SHA256: a610afab73e5bcd1...
Scala 3.8.0-RC - Size:   2509 bytes, SHA256: 86a960410c190a01...
```

**Key findings:**
- **3.0.2 and 3.1.3**: **Identical bytecode patterns** (bitmap-based inline initialization, 94 instructions)
  - SHA differs only due to minor metadata/constant pool ordering differences
  - Same field structure, same methods, same control flow
- **3.2.2**: **Minor refinement** of 3.0.x/3.1.x (bitmap-based inline, but 88 instructions)
  - Same fields: bitmap, OFFSET, typed static storage
  - Same inline accessor approach (no lzyINIT method)
  - Key difference: Uses `getDeclaredField` + `getOffsetStatic` (like 3.3+) instead of `getOffset`
  - Slightly optimized: 88 instructions vs 94
- **3.3.0 - 3.7.3**: **Identical bytecode** (stable Unsafe-based object implementation, 26 instruction accessor)
- **3.8.0+**: New VarHandle-based implementation

**Implication:** We need to handle **2 distinct input format families:**
1. **Scala 3.0.x - 3.2.x** - Bitmap-based inline implementation
   - 3.0.x/3.1.x: 94 instructions, uses `LazyVals$.getOffset`
   - 3.2.x: 88 instructions, uses `getDeclaredField` + `getOffsetStatic`
   - Both can use **same transformation strategy** (inline extraction → lzyINIT)
2. **Scala 3.3-3.7.x** - Object-based with lzyINIT (most important as 3.3.x is LTS and 3.7.x is latest pre-3.8)
   - Simple pattern replacement transformation

All must be transformed to the single 3.8+ output format.

## Field-Level Differences

For each lazy val in a class, the following fields are generated:

### 3.0.x - 3.2.x (Bitmap-based Unsafe)

```java
// Static field: memory offset for Unsafe operations on the BITMAP
public static final long OFFSET$_m_<N>;

// Instance field: bitmap tracking initialization state
public long <N>bitmap$<M>;

// Static field: stores the actual computed value (typed!)
public static <Type> <name>$lzy<N>;
```

**Key characteristics:**
- Uses a **bitmap field** (`<N>bitmap$<M>`) to track lazy val state (0=uninitialized, 1=initializing, 3=initialized)
- Stores the actual **typed value** in a static field (e.g., `int` for `Int`, not `Object`)
- OFFSET points to the **bitmap field**, not the value field
- **No separate initialization method** - all logic is inline in the accessor (94 instructions in 3.0.x/3.1.x, 88 in 3.2.x)

### 3.3-3.7.x (Unsafe-based)
```java
// Static field: memory offset for Unsafe operations
public static final long OFFSET$_m_<N>;

// Instance field: volatile state holder
private volatile Object <name>$lzy<N>;
```

### 3.8.0+ (VarHandle-based)
```java
// Static field: VarHandle for atomic operations
private static final VarHandle <name>$lzy<N>$lzyHandle;

// Instance field: volatile state holder (unchanged)
private volatile Object <name>$lzy<N>;
```

**Transformation rules:**

**3.0.x - 3.2.x → 3.8.0:**
- **Remove:** `OFFSET$_m_<N>` field (public static final long)
- **Remove:** `<N>bitmap$<M>` field (public long)
- **Remove:** `<name>$lzy<N>` typed static field (public static <Type>)
- **Add:** `<name>$lzy<N>` volatile Object field (private volatile Object)
- **Add:** `<name>$lzy<N>$lzyHandle` VarHandle field (private static final VarHandle)
- **Extract:** Inline initialization code from accessor → new `<name>$lzyINIT<N>()` method
- **Rewrite:** Accessor method to delegate to lzyINIT method

**3.3-3.7.x → 3.8.0:**
- **Remove:** `OFFSET$_m_<N>` field (public static final long)
- **Add:** `<name>$lzy<N>$lzyHandle` field (private static final VarHandle)
- **Keep:** `<name>$lzy<N>` field (unchanged)

## Method-Level Differences

### Summary of Key Differences

**3.0.x - 3.2.x** represents a fundamentally different architecture:
- **Inline initialization:** All logic in the accessor method (94 instructions in 3.0.x/3.1.x, 88 in 3.2.x)
- **Bitmap-based state:** Uses long bitmaps with bit flags for state (0=uninit, 1=initializing, 3=initialized)
- **Typed storage:** Stores actual values in typed static fields (`static int`, `static String`, etc.)
- **No lzyINIT method:** The `<name>$lzyINIT<N>()` method doesn't exist

**3.3-3.7.x** introduced the architecture still used in 3.8+:
- **Delegating accessor:** Simple 26-instruction method that delegates to lzyINIT
- **Object-based state:** Uses Object field with sentinel values (null, Evaluating$, boxed value)
- **Boxed storage:** Stores all values as Object (primitives are boxed)
- **Separate lzyINIT method:** Initialization logic extracted into dedicated method

**3.8.0** keeps the 3.3+ architecture but replaces Unsafe with VarHandle.

### 1. Static Initializer: `<clinit>()`

This method initializes the static fields needed for lazy val synchronization.

#### 3.0.x - 3.1.x Pattern (Bitmap-based Unsafe)

```bytecode
GETSTATIC scala/runtime/LazyVals$.MODULE$ : Lscala/runtime/LazyVals$;
LDC <ClassName>;.class
LDC "<N>bitmap$<M>"  ← Note: offset is for the BITMAP field!
INVOKEVIRTUAL scala/runtime/LazyVals$.getOffset (Ljava/lang/Class;Ljava/lang/String;)J
PUTSTATIC <ClassName>.OFFSET$_m_<N> : J
```

**Key characteristics:**
- Uses `LazyVals$.getOffset` (single-step, does reflection internally)
- References the **bitmap field** name (`"0bitmap$1"`) not the value field
- Does NOT use `getDeclaredField` - `getOffset` does the reflection internally
- 12 instructions total in `<clinit>`

**Example from 3.0.2/3.1.3:**
```bytecode
GETSTATIC scala/runtime/LazyVals$.MODULE$
LDC LMain$;.class
LDC "0bitmap$1"
INVOKEVIRTUAL scala/runtime/LazyVals$.getOffset (Ljava/lang/Class;Ljava/lang/String;)J
PUTSTATIC Main$.OFFSET$_m_0 : J
```

#### 3.2.x Pattern (Bitmap-based Unsafe with getDeclaredField)

```bytecode
GETSTATIC scala/runtime/LazyVals$.MODULE$ : Lscala/runtime/LazyVals$;
LDC <ClassName>;.class
LDC "<N>bitmap$<M>"  ← Still references BITMAP field!
INVOKEVIRTUAL java/lang/Class.getDeclaredField (Ljava/lang/String;)Ljava/lang/reflect/Field;
INVOKEVIRTUAL scala/runtime/LazyVals$.getOffsetStatic (Ljava/lang/reflect/Field;)J
PUTSTATIC <ClassName>.OFFSET$_m_<N> : J
```

**Key characteristics:**
- Uses `getDeclaredField` + `getOffsetStatic` (two-step like 3.3+)
- Still references the **bitmap field** name (`"0bitmap$1"`)
- Hybrid approach: bitmap-based like 3.0.x/3.1.x but uses newer reflection API
- 13 instructions total in `<clinit>` (one more than 3.0.x/3.1.x)

**Example from 3.2.2:**
```bytecode
GETSTATIC scala/runtime/LazyVals$.MODULE$
LDC LMain$;.class
LDC "0bitmap$1"
INVOKEVIRTUAL java/lang/Class.getDeclaredField (Ljava/lang/String;)Ljava/lang/reflect/Field;
INVOKEVIRTUAL scala/runtime/LazyVals$.getOffsetStatic (Ljava/lang/reflect/Field;)J
PUTSTATIC Main$.OFFSET$_m_0 : J
```

#### 3.3-3.7.x Pattern (Unsafe)

```bytecode
GETSTATIC scala/runtime/LazyVals$.MODULE$ : Lscala/runtime/LazyVals$;
LDC <ClassName>;.class
LDC "<fieldName>$lzy<N>"
INVOKEVIRTUAL java/lang/Class.getDeclaredField (Ljava/lang/String;)Ljava/lang/reflect/Field;
INVOKEVIRTUAL scala/runtime/LazyVals$.getOffsetStatic (Ljava/lang/reflect/Field;)J
PUTSTATIC <ClassName>.OFFSET$_m_<N> : J
```

**Steps:**
1. Get LazyVals singleton
2. Load class literal
3. Load field name string
4. Get Field via reflection (`Class.getDeclaredField`)
5. Convert Field to memory offset (`LazyVals$.getOffsetStatic`)
6. Store offset in `OFFSET$_m_<N>`

**Stack:** `LazyVals$ → Class → String → Field → long offset`

#### 3.8.0+ Pattern (VarHandle)

```bytecode
INVOKESTATIC java/lang/invoke/MethodHandles.lookup ()Ljava/lang/invoke/MethodHandles$Lookup;
LDC <ClassName>;.class
LDC "<fieldName>$lzy<N>"
LDC Ljava/lang/Object;.class
INVOKEVIRTUAL java/lang/invoke/MethodHandles$Lookup.findVarHandle (Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/invoke/VarHandle;
PUTSTATIC <ClassName>.<fieldName>$lzy<N>$lzyHandle : Ljava/lang/invoke/VarHandle;
```

**Steps:**
1. Get MethodHandles.Lookup instance
2. Load class literal (declaring class)
3. Load field name string
4. Load field type literal (`Object.class`)
5. Find VarHandle (`Lookup.findVarHandle`)
6. Store VarHandle in `<fieldName>$lzy<N>$lzyHandle`

**Stack:** `Lookup → Class → String → Class → VarHandle`

#### Transformation Rules

**Pattern to match:**
```
GETSTATIC scala/runtime/LazyVals$.MODULE$
LDC <Class>
LDC <FieldName>
INVOKEVIRTUAL java/lang/Class.getDeclaredField
INVOKEVIRTUAL scala/runtime/LazyVals$.getOffsetStatic
PUTSTATIC <Class>.OFFSET$_m_<N>
```

**Replace with:**
```
INVOKESTATIC java/lang/invoke/MethodHandles.lookup
LDC <Class>
LDC <FieldName>
LDC Ljava/lang/Object;.class
INVOKEVIRTUAL java/lang/invoke/MethodHandles$Lookup.findVarHandle
PUTSTATIC <Class>.<ExtractedFieldName>$lzyHandle
```

**Notes:**
- Extract field name from the `LDC` string constant
- Generate new field name: `<name>$lzy<N>$lzyHandle`
- Add field type parameter (`Object.class`)
- Max stack changes: 3 → 4

### 2. Lazy Initializer: `<name>$lzyINIT<N>()`

This method contains the lazy initialization logic with CAS operations for thread-safe initialization.

#### CAS Operation Pattern (appears ~5 times per lazy val)

Each Compare-And-Set operation follows this pattern:

##### 3.3-3.7.x Pattern (Unsafe)

```bytecode
GETSTATIC scala/runtime/LazyVals$.MODULE$ : Lscala/runtime/LazyVals$;
ALOAD 0  // this
GETSTATIC <ClassName>.OFFSET$_m_<N> : J
[expected value on stack]
[new value on stack]
INVOKEVIRTUAL scala/runtime/LazyVals$.objCAS (Ljava/lang/Object;JLjava/lang/Object;Ljava/lang/Object;)Z
```

**Stack before objCAS:** `LazyVals$ → this → offset(long) → expected → new`
**Method signature:** `objCAS(Object, long, Object, Object) → boolean`

##### 3.8.0+ Pattern (VarHandle)

```bytecode
GETSTATIC <ClassName>.<fieldName>$lzy<N>$lzyHandle : Ljava/lang/invoke/VarHandle;
ALOAD 0  // this
[expected value on stack]
[new value on stack]
INVOKEVIRTUAL java/lang/invoke/VarHandle.compareAndSet (Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Z
```

**Stack before compareAndSet:** `VarHandle → this → expected → new`
**Method signature:** `compareAndSet(Object, Object, Object) → boolean`

#### Transformation Rules

**Pattern to match:**
```
GETSTATIC scala/runtime/LazyVals$.MODULE$
ALOAD 0
GETSTATIC <Class>.OFFSET$_m_<N>
[... expected value ...]
[... new value ...]
INVOKEVIRTUAL scala/runtime/LazyVals$.objCAS
```

**Replace with:**
```
GETSTATIC <Class>.<fieldName>$lzy<N>$lzyHandle
ALOAD 0
[... expected value ...]
[... new value ...]
INVOKEVIRTUAL java/lang/invoke/VarHandle.compareAndSet
```

**Changes:**
- **Remove:** `GETSTATIC LazyVals$.MODULE$` (1 instruction)
- **Remove:** `GETSTATIC OFFSET$_m_<N>` (1 instruction)
- **Change:** GETSTATIC target from LazyVals$ to VarHandle field
- **Change:** INVOKEVIRTUAL target from objCAS to compareAndSet
- **Result:** 2 fewer instructions per CAS, simpler stack
- Max stack reduction: 7 → 5 (in the full method)

#### Typical Occurrences in `<name>$lzyINIT<N>()`

A typical lazy val initializer has **5 CAS operations:**

1. **Initial attempt:** CAS from `null` → `Evaluating$` (claiming initialization)
2. **Exception handler #1:** CAS from `Evaluating$` → previous value (release on exception)
3. **Exception handler #2:** CAS from `Waiting` → previous value (notify waiters)
4. **Success path #1:** CAS from `Evaluating$` → previous value (release after success)
5. **Success path #2:** CAS from `Waiting` → previous value (notify waiters)
6. **Contention path:** CAS from `Evaluating$` → `Waiting` (register as waiter)

Each follows the same transformation pattern.

### 3. Accessor Method: `<name>()`

The accessor method is where 3.0.x - 3.2.x diverges most dramatically from later versions.

#### 3.0.x - 3.2.x Pattern (Inline Initialization)

In 3.0.x - 3.2.x, there is **NO separate `<name>$lzyINIT<N>()` method**. All initialization logic is inline in the accessor.

- **3.0.x / 3.1.x:** 94 instructions
- **3.2.x:** 88 instructions (slightly optimized, but same overall structure)

**High-level structure:**
```bytecode
public <Type> <name>()
  // 1. Read bitmap state using Unsafe
  GETSTATIC LazyVals$.MODULE$
  ALOAD 0
  GETSTATIC OFFSET$_m_<N>  ← offset of BITMAP field!
  INVOKEVIRTUAL LazyVals$.get (Object, long) → long
  LSTORE 1  // store bitmap value

  // 2. Extract state from bitmap
  GETSTATIC LazyVals$.MODULE$
  LLOAD 1  // bitmap value
  ICONST_0  // field index
  INVOKEVIRTUAL LazyVals$.STATE (long, int) → long
  LSTORE 3  // store state (0=uninit, 1=initializing, 3=initialized)

  // 3. If state == 3 (initialized), return cached value
  LLOAD 3
  LDC 3
  LCMP
  IFNE L4
  GETSTATIC <Class>.<name>$lzy<N> : <Type>
  <I/L/F/D/A>RETURN

  // 4. If state == 0 (uninitialized), try to claim initialization
  LLOAD 3
  LCONST_0
  LCMP
  IFNE L6
  // CAS bitmap: state 0 → 1 (claiming initialization)
  GETSTATIC LazyVals$.MODULE$
  ALOAD 0
  GETSTATIC OFFSET$_m_<N>
  LLOAD 1  // current bitmap
  ICONST_1  // new state
  ICONST_0  // field index
  INVOKEVIRTUAL LazyVals$.CAS (Object, long, long, int, int) → boolean
  IFEQ L7

  // 5. We won the race, compute the value
  TRY:
    [... computation bytecode ...]
    <I/L/F/D/A>STORE 5  // store computed value
    <I/L/F/D/A>LOAD 5
    PUTSTATIC <Class>.<name>$lzy<N>  // cache the value

    // Set bitmap to state 3 (initialized)
    GETSTATIC LazyVals$.MODULE$
    ALOAD 0
    GETSTATIC OFFSET$_m_<N>
    ICONST_3  // final state
    ICONST_0  // field index
    INVOKEVIRTUAL LazyVals$.setFlag (Object, long, int, int)

    <I/L/F/D/A>LOAD 5
    <I/L/F/D/A>RETURN
  CATCH Throwable:
    // On exception, reset bitmap to 0
    GETSTATIC LazyVals$.MODULE$
    ALOAD 0
    GETSTATIC OFFSET$_m_<N>
    ICONST_0  // reset state
    ICONST_0  // field index
    INVOKEVIRTUAL LazyVals$.setFlag (Object, long, int, int)
    ATHROW

  // 6. Someone else is initializing, wait for them
  L6:
  GETSTATIC LazyVals$.MODULE$
  ALOAD 0
  GETSTATIC OFFSET$_m_<N>
  LLOAD 1  // bitmap value
  ICONST_0  // field index
  INVOKEVIRTUAL LazyVals$.wait4Notification (Object, long, long, int)
  GOTO L3  // retry from the top
```

**Key operations:**
- `LazyVals$.get(Object, long)` - read bitmap via Unsafe
- `LazyVals$.STATE(long, int)` - extract state from bitmap
- `LazyVals$.CAS(Object, long, long, int, int)` - CAS on bitmap
- `LazyVals$.setFlag(Object, long, int, int)` - update bitmap state
- `LazyVals$.wait4Notification(Object, long, long, int)` - block until initialized

**Return type:** The actual type of the lazy val (int, long, etc.), not Object!

#### 3.3-3.7.x Pattern (Delegation - 26 instructions)

```bytecode
public <Type> <name>()
  ALOAD 0
  GETFIELD <Class>.<name>$lzy<N> : Object
  ASTORE 1
  ALOAD 1
  INSTANCEOF <BoxedType>  // e.g., java/lang/Integer
  IFEQ L1
  ALOAD 1
  INVOKESTATIC BoxesRunTime.unboxTo<Type> (Object) → <type>
  <I/L/F/D/A>RETURN

 L1:
  ALOAD 1
  GETSTATIC LazyVals$NullValue$.MODULE$
  IF_ACMPNE L2
  ACONST_NULL
  INVOKESTATIC BoxesRunTime.unboxTo<Type> (Object) → <type>
  <I/L/F/D/A>RETURN

 L2:
  ALOAD 0
  INVOKESPECIAL <Class>.<name>$lzyINIT<N> () → Object
  INVOKESTATIC BoxesRunTime.unboxTo<Type> (Object) → <type>
  <I/L/F/D/A>RETURN
```

**Logic:**
1. Check if value field holds a boxed value → unbox and return
2. Check if value field holds NullValue$ → return null (unboxed)
3. Otherwise, call `<name>$lzyINIT<N>()` to initialize, unbox, and return

#### 3.8.0+ Pattern (Delegation - identical to 3.3-3.7.x!)

The accessor method in 3.8.0+ is **identical** to 3.3-3.7.x. Only the lzyINIT method differs.

#### Transformation Rules (3.0.x - 3.2.x → 3.8.0)

This is the **most complex transformation**:

1. **Extract initialization code:**
   - Identify the TRY block containing the computation
   - Extract `[... computation bytecode ...]` → body of new `<name>$lzyINIT<N>()` method
   - Box the computed value (e.g., `INVOKESTATIC Integer.valueOf`)
   - Handle exception paths (CAS to release lock, notify waiters)

2. **Create new lzyINIT method:**
   - Signature: `private <name>$lzyINIT<N>() → Object`
   - Convert bitmap-based CAS operations → VarHandle-based CAS on Object field
   - Convert `LazyVals$.get/STATE/CAS/setFlag` → `VarHandle.compareAndSet`
   - Convert state values: bitmap states 0/1/3 → Object sentinels (null/Evaluating$/boxed value)
   - Add proper exception handling with Waiting contention logic

3. **Rewrite accessor:**
   - Replace entire inline logic with delegation pattern (see 3.3-3.7.x pattern above)
   - Convert return type handling: typed value → Object → unbox
   - Add instanceof checks for boxed types
   - Add NullValue$ handling

4. **Update field storage:**
   - Replace `PUTSTATIC <name>$lzy<N> : <Type>` → `PUTFIELD <name>$lzy<N> : Object` (after boxing)
   - Replace `GETSTATIC <name>$lzy<N> : <Type>` → `GETFIELD <name>$lzy<N> : Object` (with unboxing)

**Complexity:** This requires:
- Control flow analysis to extract initialization logic
- Type analysis for proper boxing/unboxing
- Complete restructuring of the method (94 instructions → 26)
- Creating a new method from scratch

## Inner Class References

### 3.3-3.7.x
```
INNERCLASS scala/runtime/LazyVals$Evaluating$ scala/runtime/LazyVals Evaluating$
INNERCLASS scala/runtime/LazyVals$LazyValControlState scala/runtime/LazyVals LazyValControlState
INNERCLASS scala/runtime/LazyVals$NullValue$ scala/runtime/LazyVals NullValue$
INNERCLASS scala/runtime/LazyVals$Waiting scala/runtime/LazyVals Waiting
```

### 3.8.0+
```
INNERCLASS java/lang/invoke/MethodHandles$Lookup java/lang/invoke/MethodHandles Lookup
INNERCLASS scala/runtime/LazyVals$Evaluating$ scala/runtime/LazyVals Evaluating$
INNERCLASS scala/runtime/LazyVals$LazyValControlState scala/runtime/LazyVals LazyValControlState
INNERCLASS scala/runtime/LazyVals$NullValue$ scala/runtime/LazyVals NullValue$
INNERCLASS scala/runtime/LazyVals$Waiting scala/runtime/LazyVals Waiting
```

**Transformation rule:**
- **Add:** `MethodHandles$Lookup` inner class reference

## Unchanged Methods

The following methods are **identical** across versions and require no transformation:

- `<init>()` - Constructor
- `<name>()` - The lazy val accessor (delegates to lzyINIT)
- `main([Ljava/lang/String;)V` - Application entry point
- `writeReplace()` - Serialization support

## Detection Strategy

To identify classes that need transformation:

1. **Field-based detection:**
   - Search for static fields matching `OFFSET$_m_<N>` pattern
   - These indicate 3.x-3.7.x lazy vals

2. **Method-based detection:**
   - Search for `<clinit>` containing `scala/runtime/LazyVals$.getOffsetStatic`
   - Search for methods containing `scala/runtime/LazyVals$.objCAS`

3. **Conservative approach:**
   - If any lazy val pattern is detected, transform the entire class
   - Preserve all other bytecode exactly

## Implementation Notes

### Constant Pool Considerations

The transformation requires adding new constant pool entries:
- `java/lang/invoke/MethodHandles`
- `java/lang/invoke/MethodHandles$Lookup`
- `java/lang/invoke/VarHandle`
- Method references to `lookup()`, `findVarHandle()`, `compareAndSet()`

ASM handles this automatically when using the Tree API or when visiting instructions.

### Stack Map Frames

Stack map frames may need updates due to:
- Different max stack sizes (e.g., 7 → 5, 3 → 4)
- Different instruction counts

ASM can recompute frames automatically using `ClassWriter.COMPUTE_FRAMES`.

### Multiple Lazy Vals

For classes with multiple lazy vals:
- Each lazy val has its own `OFFSET$_m_<N>` → needs corresponding `<name>$lzy<N>$lzyHandle`
- Each `<clinit>` pattern must be transformed independently
- Each `<name>$lzyINIT<N>()` method must be transformed independently
- Pattern matching must extract the index `<N>` to correlate fields and methods

## Testing Strategy

For each Scala version (3.0.x, 3.1.x, 3.2.x, 3.3-3.7.x):

1. **Compile test cases** with that version
2. **Transform bytecode** to 3.8+ format
3. **Compare transformed output** with 3.8+-compiled output using bytecode diff tool
4. **Verify identical behavior:**
   - Same fields (modulo offset vs VarHandle)
   - Same method structure
   - Same CAS semantics
   - Same max stack/locals
5. **Runtime testing:**
   - Execute transformed bytecode on JDK 26+
   - Verify lazy initialization happens once
   - Verify thread safety under concurrent access

## Implementation Complexity

### 3.3-3.7.x → 3.8.0 (Moderate)
- Straightforward pattern matching and replacement
- No control flow changes
- No type conversions required
- Can be done with simple ASM MethodVisitor

### 3.0.x - 3.2.x → 3.8.0 (Very Complex)
- Requires complete method restructuring
- Control flow extraction (inline → separate method)
- Type conversions (primitive → boxed Object)
- Field access changes (static → instance)
- State representation changes (bitmap long → Object sentinels)
- Requires ASM Tree API for analysis and reconstruction
- Two slightly different patterns to detect:
  - 3.0.x/3.1.x: `LazyVals$.getOffset` (single call)
  - 3.2.x: `getDeclaredField` + `getOffsetStatic` (two calls)
- Both use same transformation strategy (minor detection differences only)

**Recommendation:** Implement 3.3-3.7.x transformation first to prove the concept, then tackle 3.0.x - 3.2.x as phase 2.

## Open Questions

1. ✅ **Version analysis complete:**
   - **3.0.2 ≡ 3.1.3:** Bytecode-identical (bitmap-based, 94 instructions, uses `getOffset`)
   - **3.2.x:** Minor refinement (bitmap-based, 88 instructions, uses `getDeclaredField` + `getOffsetStatic`)
   - **3.3-3.7.x:** Complete redesign (object-based, 26 instruction accessor, separate lzyINIT method)
2. **Edge cases:** Lazy vals in traits, objects, nested classes - do they follow the same patterns?
3. **Generic lazy vals:** Do type parameters affect the field type in 3.3+? (Currently assuming always `Object`)
4. **Object vs primitive:** In 3.0.x - 3.2.x, we saw `Int` stored as `static int`. Do reference types (e.g., `lazy val s: String`) follow the same pattern or already use Object?
5. **Multiple lazy vals in 3.0.x - 3.2.x:** How are bitmap fields allocated? One bitmap per lazy val or shared bitmaps?
6. **Boxing behavior:** What boxing primitives are used for each type? (Integer.valueOf, Long.valueOf, etc.)
7. **NullValue$ semantics:** When is NullValue$ used vs actual null in the Object field?

## References

- [Scala 3.8 Release Notes](https://github.com/scala/scala3/releases)
- [JEP 260: Encapsulate Most Internal APIs](https://openjdk.org/jeps/260) (sun.misc.Unsafe removal)
- [JEP 193: Variable Handles](https://openjdk.org/jeps/193) (VarHandle introduction in Java 9)
- ASM documentation: https://asm.ow2.io/
