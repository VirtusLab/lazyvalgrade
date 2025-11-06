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

**Classes (instance lazy vals):**
```java
public static final long OFFSET$<N>;      // Points to bitmap field
public long <N>bitmap$<M>;                // Instance bitmap
public <Type> <name>$lzy<N>;              // Instance typed storage (String, int, etc.)
```

**Objects (static lazy vals):**
```java
public static final long OFFSET$_m_<N>;   // _m_ = module/static
public static long <N>bitmap$<M>;         // Static bitmap
public static <Type> <name>$lzy<N>;       // Static typed storage
```

**Detection:**
- `<N>bitmap$<M>` field exists (long)
- Typed storage field (not Object)
- No `<name>$lzyINIT<N>()` method
- Accessor 125-127 instructions
- <clinit> references `"<N>bitmap$<M>"` string
- 3.0.x/3.1.x: uses `LazyVals$.getOffset`
- 3.2.x: uses `Class.getDeclaredField` + `LazyVals$.getOffsetStatic`

### 3.3-3.7.x (Unsafe-based)

**For lazy vals in classes (instance members):**
```java
// Static field: memory offset for Unsafe operations on the instance field
public static final long OFFSET$<N>;

// Instance field: volatile state holder
private volatile Object <name>$lzy<N>;
```

**For lazy vals in objects (module classes):**
```java
// Static field: memory offset for Unsafe operations on the static field
public static final long OFFSET$_m_<N>;

// Static field: volatile state holder
private volatile Object <name>$lzy<N>;
```

**Key insight:** The OFFSET field visibility is PUBLIC in both cases because:
- It's a compile-time constant needed for Unsafe operations
- It points to either an instance field (in classes) or a static field (in objects)
- The storage field itself (`<name>$lzy<N>`) is always PRIVATE to encapsulate the lazy val state

### 3.8.0+ (VarHandle-based)

**For lazy vals in classes (instance members):**
```java
// Static field: VarHandle for atomic operations on the instance field
private static final VarHandle <name>$lzy<N>$lzyHandle;

// Instance field: volatile state holder (unchanged)
private volatile Object <name>$lzy<N>;
```

**For lazy vals in objects (module classes):**
```java
// Static field: VarHandle for atomic operations on the static field
private static final VarHandle <name>$lzy<N>$lzyHandle;

// Static field: volatile state holder (unchanged)
private volatile Object <name>$lzy<N>;
```

**Key insight:** VarHandle is PRIVATE (unlike OFFSET which was PUBLIC) because:
- VarHandle is an opaque capability object (better encapsulation)
- It's not exposed through sun.misc.Unsafe which required offsets to be accessible
- The JVM's VarHandle API provides stronger encapsulation guarantees

**Transformation rules:**

**3.0.x - 3.2.x → 3.8.0:**

Fields:
- **Remove:** `OFFSET$<N>` or `OFFSET$_m_<N>` (public static final long)
- **Remove:** `<N>bitmap$<M>` (public long or public static long)
- **Change:** `<name>$lzy<N>` from `public <Type>` to `private volatile Object` (instance or static based on original)
- **Add:** `<name>$lzy<N>$lzyHandle` (private static final VarHandle)

Methods:
- **Create:** `<name>$lzyINIT<N>()` method by extracting inline logic from accessor
  - Convert bitmap CAS: `LazyVals$.CAS(Object, long, long, int, int)` → `VarHandle.compareAndSet(Object, Object, Object)`
  - Convert state values: 0 → null, 1 → Evaluating$, 3 → computed value
  - Add boxing for primitives (int → Integer.valueOf, etc.)
  - Add NullValue$ wrapping for null results
  - Replace `setFlag` calls with CAS operations
  - Replace `wait4Notification` with Waiting.await() pattern
- **Rewrite:** Accessor to 34-instruction delegation pattern (load field, instanceof checks, call lzyINIT, unbox if needed)

<clinit>:
- Replace OFFSET initialization with VarHandle.findVarHandle pattern
- Change field name from `"<N>bitmap$<M>"` to `"<name>$lzy<N>"`
- Add field type: `Object.class`

**3.3-3.7.x → 3.8.0:**

Fields:
- **Remove:** `OFFSET$<N>` or `OFFSET$_m_<N>` (public static final long)
- **Add:** `<name>$lzy<N>$lzyHandle` (private static final VarHandle)
- **Keep:** `<name>$lzy<N>` unchanged

Methods:
- **<clinit>:** Replace OFFSET init with VarHandle.findVarHandle
- **<name>$lzyINIT<N>():** Replace all objCAS calls:
  - Remove: GETSTATIC LazyVals$.MODULE$, GETSTATIC OFFSET
  - Add: GETSTATIC <name>$lzyHandle
  - Change: INVOKEVIRTUAL objCAS → compareAndSet
  - Stack: 7 → 5
- **<name>():** No changes

## Class vs Object Lazy Vals: A Critical Distinction

### Understanding the Two Scenarios

Lazy vals can appear in two fundamentally different contexts, which affect their bytecode generation:

1. **Object lazy vals** (in Scala `object` declarations) → compiled to static fields in module classes (`Main$`, `Foo$`)
2. **Class lazy vals** (in Scala `class` declarations) → compiled to instance fields in regular classes (`Foo`, `Bar`)

### Example Source Code

**Object lazy val:**
```scala
object Main:
  lazy val simpleLazy: Int = 42  // Static in Main$.class
```

**Class lazy val:**
```scala
class Foo:
  lazy val a: String = "test"  // Instance field in Foo.class
```

### Bytecode Differences: 3.3-3.7.x (Unsafe-based)

#### Object Lazy Val (Main$.class)

**Fields:**
```java
// OFFSET points to the STATIC field simpleLazy$lzy1
public static final long OFFSET$_m_0;     // Note: _m_ for "module" / static

// The lazy val storage itself
private volatile Object simpleLazy$lzy1;   // static field (in object)
```

**Static initializer `<clinit>`:**
```bytecode
getstatic LazyVals$.MODULE$
ldc LMain$;.class
ldc "simpleLazy$lzy1"                     // Field name
invokevirtual Class.getDeclaredField
invokevirtual LazyVals$.getOffsetStatic   // Returns offset of STATIC field
putstatic OFFSET$_m_0                     // Store offset
```

**CAS operation in `simpleLazy$lzyINIT0()`:**
```bytecode
getstatic LazyVals$.MODULE$
getstatic Main$.MODULE$                   // Load the singleton instance
getstatic OFFSET$_m_0                     // Load offset (points to static field)
aconst_null                               // Expected value
getstatic Evaluating$.MODULE$             // New value
invokevirtual LazyVals$.objCAS            // CAS on static field
```

**Key insight:** The CAS operates on a **static field**, so we use `getstatic Main$.MODULE$` to get the singleton instance, then use the OFFSET to locate the static field `simpleLazy$lzy1` within it.

#### Class Lazy Val (Foo.class)

**Fields:**
```java
// OFFSET points to the INSTANCE field a$lzy1
public static final long OFFSET$0;        // Note: no _m_, just $0

// The lazy val storage (one per instance!)
private volatile Object a$lzy1;           // instance field
```

**Static initializer `<clinit>`:**
```bytecode
getstatic LazyVals$.MODULE$
ldc LFoo;.class
ldc "a$lzy1"                              // Field name
invokevirtual Class.getDeclaredField
invokevirtual LazyVals$.getOffsetStatic   // Returns offset of INSTANCE field
putstatic OFFSET$0                        // Store offset
```

**CAS operation in `a$lzyINIT1()`:**
```bytecode
getstatic LazyVals$.MODULE$
aload_0                                   // Load THIS (current Foo instance)
getstatic OFFSET$0                        // Load offset (points to instance field)
aconst_null                               // Expected value
getstatic Evaluating$.MODULE$             // New value
invokevirtual LazyVals$.objCAS            // CAS on instance field
```

**Key insight:** The CAS operates on an **instance field**, so we use `aload_0` (this) to reference the current instance, then use the OFFSET to locate the instance field `a$lzy1` within it.

### Why OFFSET is PUBLIC in Both Cases

You might wonder: "Why is `OFFSET$0` or `OFFSET$_m_0` public when `a$lzy1` is private?"

**Answer:**
- The OFFSET is a **memory address constant** computed at class initialization time
- It's part of the Unsafe API contract - offsets must be accessible for operations
- Making it public doesn't leak information because:
  - You can't access the field's value with just the offset
  - You still need Unsafe access (which is restricted)
  - The storage field (`a$lzy1`) remains private

### Bytecode Differences: 3.8.0+ (VarHandle-based)

The 3.8.0 transformation changes the synchronization mechanism but **preserves the class vs object distinction**:

#### Object Lazy Val (Main$.class) - 3.8.0

**Fields:**
```java
// VarHandle points to the STATIC field simpleLazy$lzy1
private static final VarHandle simpleLazy$lzy1$lzyHandle;

// The lazy val storage (static)
private volatile Object simpleLazy$lzy1;
```

**Static initializer `<clinit>`:**
```bytecode
invokestatic MethodHandles.lookup
ldc LMain$;.class
ldc "simpleLazy$lzy1"                     // Field name
ldc Ljava/lang/Object;.class              // Field type
invokevirtual MethodHandles$Lookup.findVarHandle
putstatic simpleLazy$lzy1$lzyHandle       // Store VarHandle
```

**CAS operation in `simpleLazy$lzyINIT0()`:**
```bytecode
getstatic simpleLazy$lzy1$lzyHandle       // Load VarHandle
getstatic Main$.MODULE$                   // Load the singleton instance
aconst_null                               // Expected value
getstatic Evaluating$.MODULE$             // New value
invokevirtual VarHandle.compareAndSet     // CAS on static field
```

#### Class Lazy Val (Foo.class) - 3.8.0

**Fields:**
```java
// VarHandle points to the INSTANCE field a$lzy1
private static final VarHandle a$lzy1$lzyHandle;

// The lazy val storage (instance)
private volatile Object a$lzy1;
```

**Static initializer `<clinit>`:**
```bytecode
invokestatic MethodHandles.lookup
ldc LFoo;.class
ldc "a$lzy1"                              // Field name
ldc Ljava/lang/Object;.class              // Field type
invokevirtual MethodHandles$Lookup.findVarHandle
putstatic a$lzy1$lzyHandle                // Store VarHandle
```

**CAS operation in `a$lzyINIT1()`:**
```bytecode
getstatic a$lzy1$lzyHandle                // Load VarHandle
aload_0                                   // Load THIS (current Foo instance)
aconst_null                               // Expected value
getstatic Evaluating$.MODULE$             // New value
invokevirtual VarHandle.compareAndSet     // CAS on instance field
```

### Stack Depth Reduction: Unsafe → VarHandle

The transformation from Unsafe to VarHandle reduces stack requirements:

**3.3-3.7.x (Unsafe):** `stack=7`
```
Stack: [LazyVals$, this/singleton, offset_high, offset_low, expected, new, ...]
```

**3.8.0+ (VarHandle):** `stack=5`
```
Stack: [VarHandle, this/singleton, expected, new, ...]
```

**Why the reduction?**
- Unsafe's `objCAS` takes a `long` offset (2 stack slots: high + low 32 bits)
- VarHandle encapsulates the field reference (no offset parameter needed)
- Result: 2 fewer stack slots required per CAS operation

### Detection Implications for Transformation

When transforming bytecode, you must:

1. **Detect the lazy val type:**
   - Look for `OFFSET$_m_<N>` → object/module lazy val (static field)
   - Look for `OFFSET$<N>` (without `_m_`) → class lazy val (instance field)

2. **Preserve the instance vs static distinction:**
   - Object lazy vals: `getstatic MODULE$` in CAS operations
   - Class lazy vals: `aload_0` in CAS operations

3. **Generate correct VarHandle field name:**
   - Extract base name from OFFSET field
   - Object: `simpleLazy$lzy1$lzyHandle` (matches `OFFSET$_m_0` → `simpleLazy$lzy1`)
   - Class: `a$lzy1$lzyHandle` (matches `OFFSET$0` → `a$lzy1`)

4. **Update field access modifiers:**
   - 3.3-3.7.x: `public static final long OFFSET$...`
   - 3.8.0+: `private static final VarHandle ...$lzyHandle`

### Summary Table

| Aspect | Object Lazy Val (3.3-3.7.x) | Class Lazy Val (3.3-3.7.x) | Both in 3.8.0+ |
|--------|----------------------------|---------------------------|----------------|
| **OFFSET field** | `public static final long OFFSET$_m_<N>` | `public static final long OFFSET$<N>` | **Removed** |
| **VarHandle field** | N/A | N/A | `private static final VarHandle <name>$lzy<N>$lzyHandle` |
| **Storage field** | `private volatile Object <name>$lzy<N>` (static) | `private volatile Object <name>$lzy<N>` (instance) | **Unchanged** |
| **CAS receiver** | `getstatic MODULE$` (singleton) | `aload_0` (this) | **Same** |
| **Stack depth** | 7 (LazyVals$ + singleton + long offset + 2 values) | 7 (LazyVals$ + this + long offset + 2 values) | 5 (VarHandle + this/singleton + 2 values) |

## Companion Class Lazy Vals: No Split Pattern

**Source:** `class Foo { lazy val a = "test" }` with `object Foo { def apply() = new Foo() }` companion

This case follows the **standard class lazy val pattern** (see "Class vs Object Lazy Vals" section above). The presence of a companion object **does not** affect lazy val compilation when the lazy val is in the class.

### 3.3-3.7.x Pattern

**In Foo.class (the class with the lazy val):**
- Field: `public static final long OFFSET$0` (no `_m_` suffix - instance field offset)
- Field: `private volatile Object a$lzy1` (instance field)
- `<clinit>`:
  ```
  getstatic LazyVals$.MODULE$
  ldc Foo.class               ← Self-reference!
  ldc "a$lzy1"
  getDeclaredField
  getOffsetStatic
  putstatic Foo.OFFSET$0
  ```
- Method `a()`: Standard 34-instruction delegating accessor
- Method `a$lzyINIT1()`: Uses `aload_0` (this) and `getstatic OFFSET$0` with objCAS

**In Foo$.class (companion object):**
- **No lazy val infrastructure**
- Only MODULE$ field, apply() method, standard companion boilerplate
- Identical across 3.3.0-3.7.3 (SHA: 31b79c2490369638d82f508c0824650742db838f0497d957c31091f6a64287f4)

### 3.8.0+ Pattern

**In Foo.class:**
- Field: `private static final VarHandle a$lzy1$lzyHandle`
- Field: `private volatile Object a$lzy1` (instance field)
- `<clinit>`:
  ```
  invokestatic MethodHandles.lookup
  ldc Foo.class
  ldc "a$lzy1"
  ldc Object.class
  findVarHandle
  putstatic a$lzy1$lzyHandle
  ```
- Method `a()`: Identical 34-instruction delegating accessor
- Method `a$lzyINIT1()`: Uses VarHandle.compareAndSet with `aload_0` (this)

**In Foo$.class:**
- **Identical to 3.3-3.7.x** (SHA: 31b79c2490369638d82f508c0824650742db838f0497d957c31091f6a64287f4)
- No lazy val infrastructure

### Transformation

This case transforms exactly like a standalone class lazy val:
- Remove `OFFSET$0` field from Foo.class
- Add `a$lzy1$lzyHandle` field to Foo.class
- Update `<clinit>` in Foo.class to use VarHandle.findVarHandle
- Transform `a$lzyINIT1()` in Foo.class: objCAS → compareAndSet
- **No changes to Foo$.class** (companion object is not involved)

### Detection

**3.3-3.7.x:**
- Class has `OFFSET$<N>` field (no `_m_` suffix)
- Class has `private volatile Object <name>$lzy<N>` instance field
- Class `<clinit>` references its own class (`ldc LFoo;.class`)
- Companion object has no lazy val fields

**3.8.0+:**
- Class has `private static final VarHandle <name>$lzy<N>$lzyHandle`
- Companion object unchanged

## Companion Object Lazy Vals: Split-Class Pattern

**Source:** `object Foo { lazy val a = "test" }` with `class Foo` companion

### 3.0-3.2.x Pattern

**In `Foo.class` (companion class):**
- Field: `public static final long OFFSET$_m_0`
- `<clinit>`:
  - 3.0.x/3.1.x:
    ```
    getstatic LazyVals$.MODULE$
    ldc Foo$.class
    ldc "0bitmap$1"            ← Bitmap field in module
    getOffset
    putstatic Foo.OFFSET$_m_0
    ```
  - 3.2.x:
    ```
    getstatic LazyVals$.MODULE$
    ldc Foo$.class
    ldc "0bitmap$1"
    getDeclaredField
    getOffsetStatic
    putstatic Foo.OFFSET$_m_0
    ```

**In `Foo$.class` (module class):**
- Field: `public long 0bitmap$1` (instance field on MODULE$ singleton)
- Field: `public static String a$lzy1` (static typed storage)
- Accessor `a()` has inline initialization (125 instructions)
- Uses `getstatic Foo.OFFSET$_m_0` to read bitmap offset
- Bitmap-based state: 0=uninit, 1=initializing, 3=initialized

**Key difference from standalone objects:** Bitmap is an **instance field** (on the MODULE$ singleton), not static, because the module class itself is instantiated once as a singleton.

### 3.3-3.7.x Pattern

**In `Foo.class` (companion class):**
- Field: `public static final long OFFSET$_m_0`
- `<clinit>`:
  ```
  getstatic LazyVals$.MODULE$
  ldc Foo$.class              ← References module class
  ldc "a$lzy1"                ← Field name in module
  getDeclaredField
  getOffsetStatic
  putstatic Foo.OFFSET$_m_0   ← Stores in companion
  ```

**In `Foo$.class` (module class):**
- Field: `private volatile Object a$lzy1` (the storage)
- Method `a$lzyINIT1()` uses:
  ```
  getstatic Foo.OFFSET$_m_0   ← Reads from companion class!
  aload_0
  ...
  objCAS
  ```
- Accessor `a()` delegates to `a$lzyINIT1()`

### 3.8.0+ Pattern

**In `Foo.class` (companion class):**
- **No OFFSET field**
- **No <clinit>**
- Only forwarding method `a()` remains

**In `Foo$.class` (module class):**
- Field: `private static final VarHandle a$lzy1$lzyHandle`
- Field: `private volatile Object a$lzy1`
- `<clinit>`:
  ```
  invokestatic MethodHandles.lookup
  ldc Foo$.class              ← Self-reference now
  ldc "a$lzy1"
  ldc Object.class
  findVarHandle
  putstatic a$lzy1$lzyHandle  ← Stores in module
  ```

### Detection

**3.0-3.2.x companion objects:**
- Companion class has `OFFSET$_m_<N>` field
- Companion class `<clinit>` references bitmap field `"<N>bitmap$<M>"` in module
- Module class has instance bitmap field `<N>bitmap$<M>`
- Module class has static typed storage field `<name>$lzy<N>`
- Module accessor has inline initialization (125 instructions)
- Module accessor reads `OFFSET$_m_<N>` from companion

**3.3-3.7.x companion objects:**
- Companion class has `OFFSET$_m_<N>` field
- Companion class `<clinit>` references `Foo$.class` and field in module
- Module class has storage field and lzyINIT method
- Module's lzyINIT reads `OFFSET$_m_<N>` from companion

**3.8.0+ companion objects:**
- Companion class has NO OFFSET field
- Module class has VarHandle field (self-contained)

### Transformation

**3.0-3.2.x → 3.8.0:**

*Companion class (Foo.class):*
- Remove `OFFSET$_m_<N>` field
- Remove or simplify `<clinit>` (if only contains OFFSET init)

*Module class (Foo$.class):*
- Remove instance bitmap field `<N>bitmap$<M>`
- Change storage field from `public static <Type> <name>$lzy<N>` to `private volatile Object <name>$lzy<N>` (static)
- Add `private static final VarHandle <name>$lzy<N>$lzyHandle` field
- Add `<clinit>` initialization for VarHandle (if not exists, otherwise extend existing)
- Extract inline accessor logic → new `<name>$lzyINIT<N>()` method
- Rewrite accessor to delegation pattern (34 instructions)
- Convert bitmap CAS → VarHandle.compareAndSet on Object field
- Convert state values: 0→null, 1→Evaluating$, 3→computed value
- Add boxing for typed values, NullValue$ wrapping for nulls

**3.3-3.7.x → 3.8.0:**

*Companion class (Foo.class):*
- Remove `OFFSET$_m_<N>` field
- Remove or simplify `<clinit>` (if only contains OFFSET init)

*Module class (Foo$.class):*
- Add `a$lzy1$lzyHandle` field
- Update `<clinit>` to use `findVarHandle` pattern
- Transform `a$lzyINIT1()` CAS operations: objCAS → compareAndSet
- Change `getstatic Foo.OFFSET$_m_0` → `getstatic Foo$.a$lzy1$lzyHandle`

### Bytecode Stability

**3.0.2-3.2.2:**
- **Foo$.class (module):**
  - 3.0.2: 1321 bytes, SHA 1c5951de23819702...
  - 3.1.3: 1341 bytes, SHA e3ed826fe745a799... (adds Signature attribute)
  - 3.2.2: 1319 bytes, SHA 8d6eeadb840542fa... (structurally similar to 3.0.2/3.1.3, inline accessor)
- **Foo.class (companion):**
  - 3.0.2: 651 bytes, SHA a39d11cab5e50147... (uses `getOffset`)
  - 3.1.3: 671 bytes, SHA 890fe819382ce6a2... (adds Signature attribute)
  - 3.2.2: 768 bytes, SHA b6e73801d51b5e0a... (uses `getDeclaredField + getOffsetStatic`)

**3.3.0-3.7.3:**
- **Foo$.class (module):** Completely identical across all versions (SHA: 642a5914e20cec97..., 1828 bytes)
- **Foo.class (companion):** Structurally identical, only TASTY metadata differs
  - 3.3.0: SHA 7c005a3dba5ee48b...
  - 3.5.2/3.7.3: SHA 465bb5bf90e4bb42... (identical)

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

#### Complete Control Flow: Understanding `<name>$lzyINIT<N>()`

The `lzyINIT` method implements a sophisticated state machine for thread-safe lazy initialization. Here's the complete flow with concrete examples from `class Foo { lazy val a = "test" }`:

##### State Machine Values

The `a$lzy1` field can hold these values:

| Value | Meaning | Type |
|-------|---------|------|
| `null` | Uninitialized, no thread is initializing | Initial state |
| `Evaluating$` | A thread is currently computing the value | Control state sentinel |
| `Waiting` | One or more threads are waiting for initialization | Control state sentinel |
| `"test"` | Initialized, holds the actual computed value | Final value (String) |
| `NullValue$` | Initialized, the computation returned null | Sentinel for null |

##### Control Flow Walkthrough

**Thread 1 enters `a$lzyINIT1()` first:**

```
1. Load a$lzy1 → null
2. Branch: is null? YES → fast path
3. CAS(this, null → Evaluating$) → SUCCESS (we claimed it!)
4. TRY:
     5. Execute computation: ldc "test"
     6. Store to local variable 3
     7. Is result null? NO
     8. Store result directly to local 2
9. CAS(this, Evaluating$ → "test") → SUCCESS (we published it!)
10. If CAS failed (someone set it to Waiting):
      11. Get a$lzy1 → cast to Waiting
      12. CAS(this, Waiting → "test")
      13. Call waiting.countDown() to wake waiters
14. Return "test"
```

**Thread 2 enters while Thread 1 is at step 5 (computing):**

```
1. Load a$lzy1 → Evaluating$ (Thread 1 owns it)
2. Branch: is null? NO → slow path
3. Is LazyValControlState? YES
4. Is Evaluating$? YES
5. CAS(this, Evaluating$ → new Waiting()) → SUCCESS
   (We registered ourselves as waiting)
6. Loop back to step 1
7. Load a$lzy1 → Waiting (our own Waiting object)
8. Is LazyValControlState? YES
9. Is Evaluating$? NO
10. Is Waiting? YES
11. Call waiting.await() → BLOCKS until Thread 1 calls countDown()
12. Loop back to step 1
13. Load a$lzy1 → "test" (Thread 1 finished!)
14. Is LazyValControlState? NO
15. Return "test"
```

**Thread 3 enters after Thread 1 published "test":**

```
1. Load a$lzy1 → "test"
2. Branch: is null? NO → slow path
3. Is LazyValControlState? NO
4. Return "test" immediately (fast exit)
```

##### Exception Handling

If Thread 1 throws an exception during computation:

```
TRY:
  Execute computation → THROWS
CATCH:
  1. CAS(this, Evaluating$ → local_2)
     (Attempt to restore previous value, likely null or NullValue$)
  2. If CAS failed:
       3. Get a$lzy1 → cast to Waiting
       4. CAS(this, Waiting → local_2)
       5. Call waiting.countDown() to wake waiters
  6. Rethrow exception
```

**Result:** The field goes back to its pre-initialization state, allowing another thread to retry.

##### CAS Operations Count

A typical lazy val initializer has **5 CAS operations:**

1. **Initial claim:** CAS `null` → `Evaluating$` (claim initialization right)
2. **Exception cleanup #1:** CAS `Evaluating$` → previous value (release on exception)
3. **Exception cleanup #2:** CAS `Waiting` → previous value (notify waiters after exception)
4. **Success publication #1:** CAS `Evaluating$` → computed value (publish result)
5. **Success publication #2:** CAS `Waiting` → computed value (publish result + notify waiters)

Plus one additional CAS in the contention path:
6. **Contention registration:** CAS `Evaluating$` → `new Waiting()` (register as waiter)

Each follows the same transformation pattern: `objCAS` → `compareAndSet`, with stack size reduction from 7 to 5.

##### Why This Design?

**Benefits:**
- **Lock-free fast path:** Once initialized, returns immediately (no synchronization)
- **No busy-waiting:** Uses `Waiting.await()` instead of spinning
- **Exception safety:** Failed initialization doesn't block other threads
- **Memory efficient:** No separate lock objects until contention occurs

**Trade-offs:**
- Complex state machine with multiple CAS operations
- Requires boxing for all values (primitives → Integer, Long, etc.)
- Uses sentinel objects (Evaluating$, NullValue$) for control flow

### 3. Accessor Method: `<name>()`

**IMPORTANT:** The accessor method is **IDENTICAL** in 3.3-3.7.x and 3.8.0! This is a crucial insight for transformation.

The accessor method is where 3.0.x - 3.2.x diverges most dramatically from later versions.

#### 3.3-3.7.x and 3.8.0 Pattern (Identical!)

Both versions use the exact same delegation pattern (34 instructions):

```bytecode
public <Type> <name>()
  ALOAD 0
  GETFIELD <Class>.<name>$lzy<N> : Object
  ASTORE 1
  ALOAD 1
  INSTANCEOF <BoxedType>  // e.g., java/lang/String for String, java/lang/Integer for Int
  IFEQ L1
  ALOAD 1
  CHECKCAST <BoxedType>
  <A/invokestatic BoxesRunTime.unboxTo<Type>>  // Only for primitives
  <I/L/F/D/A>RETURN

 L1:
  ALOAD 1
  GETSTATIC LazyVals$NullValue$.MODULE$
  IF_ACMPNE L2
  ACONST_NULL
  <invokestatic BoxesRunTime.unboxTo<Type>>  // Only for primitives
  <I/L/F/D/A>RETURN

 L2:
  ALOAD 0
  INVOKESPECIAL <Class>.<name>$lzyINIT<N> () → Object
  CHECKCAST <BoxedType>
  <invokestatic BoxesRunTime.unboxTo<Type>>  // Only for primitives
  <I/L/F/D/A>RETURN
```

**Logic breakdown:**

1. **Check if already initialized (fast path):**
   - Load the `<name>$lzy<N>` field
   - If it's an instance of the expected type → return it (possibly unboxing)

2. **Check if null value:**
   - If field holds `NullValue$` sentinel → return null (for lazy vals that compute to null)

3. **Initialization needed (slow path):**
   - Call `<name>$lzyINIT<N>()` to perform thread-safe initialization
   - Return the result (possibly unboxing)

**For reference types (e.g., String):**
```bytecode
// Fast path: field holds "test"
GETFIELD a$lzy1
INSTANCEOF String → true
CHECKCAST String
ARETURN  // Return the String directly
```

**For primitive types (e.g., Int):**
```bytecode
// Fast path: field holds Integer(42)
GETFIELD simpleLazy$lzy1
INSTANCEOF Integer → true
CHECKCAST Integer
INVOKESTATIC BoxesRunTime.unboxToInt  // Convert Integer → int
IRETURN  // Return primitive int
```

**Why this method is unchanged in 3.8.0:**

The accessor only reads the `<name>$lzy<N>` field and delegates to `<name>$lzyINIT<N>()`. The synchronization mechanism (Unsafe vs VarHandle) is completely encapsulated in the lzyINIT method, so the accessor doesn't need to change at all!

**Transformation implication:** When transforming from 3.3-3.7.x to 3.8.0, you can **leave the accessor method completely untouched**. Only transform:
1. The `<clinit>` static initializer (OFFSET → VarHandle)
2. The `<name>$lzyINIT<N>()` initialization method (objCAS → compareAndSet)

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

**Important: Lazy Val Index Convention**

All lazy vals in a single class/object share the **same index** (typically 1):
```scala
object MultipleLazyVals:
  lazy val first: String = "one"     // index=1
  lazy val second: Int = 42          // index=1
  lazy val third: Double = 3.14      // index=1
  lazy val fourth: Boolean = true    // index=1
```

This means:
- Storage fields: `first$lzy1`, `second$lzy1`, `third$lzy1`, `fourth$lzy1`
- OFFSET fields: `OFFSET$_m_0`, `OFFSET$_m_1`, `OFFSET$_m_2`, `OFFSET$_m_3` (one per lazy val)
- VarHandle fields: `first$lzy1$lzyHandle`, `second$lzy1$lzyHandle`, etc.

The index in the storage field name (`$lzy1`) is NOT unique per lazy val - it's constant for all lazy vals in the class. The OFFSET field index (`$_m_N`) is what distinguishes different lazy vals, where N = lazy val index - 1 (so N=0 for index=1).

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

## Bytecode Stability Across Versions

### Confirmed Stable Ranges

Based on detailed bytecode analysis of the `class-lazy-val` example:

**3.3.0 → 3.7.3:**
- **3.3.0:** SHA-256 `9542f4f20e718d718564ffef00cbd61b27327751058c2608b4db662ad7da738f` (1799 bytes)
- **3.5.2 ≡ 3.7.3:** SHA-256 `eb546b81bef4716093e211bc23130af114f1e454a1ccb15a59d6082f0b26d30e` (1799 bytes)

**Differences:**
- 3.3.0 vs 3.5.2/3.7.3: Only TASTY metadata differs (bytes at end of file)
- **All structural bytecode is identical:** same fields, same methods, same instructions, same constant pool entries
- The difference is cosmetic (compiler metadata), not functional

**Implication:** A single transformation strategy works for **all of 3.3.0 through 3.7.3**. The lazy val implementation remained completely stable across 4 major and 10+ minor Scala releases.

### Summary

1. ✅ **Version analysis complete:**
   - **3.0.2 ≡ 3.1.3:** Structurally identical (bitmap-based, 127 instructions, uses `getOffset`)
     - 3.0.2: 1174 bytes, different TASTY
     - 3.1.3: 1194 bytes (adds Signature attribute), same TASTY
   - **3.2.2:** Structurally identical to 3.0.x/3.1.x but different <clinit> (125 instructions, uses `getDeclaredField` + `getOffsetStatic`)
     - 1269 bytes, same TASTY as 3.0.x/3.1.x
   - **3.3.0 - 3.7.3:** Structurally identical (object-based, 34 instruction accessor, separate lzyINIT method)
     - 3.3.0: One TASTY hash
     - 3.5.2 ≡ 3.7.3: Identical TASTY hash
   - **3.8.0+:** VarHandle-based (same structure as 3.3+, different synchronization primitive)

## Open Questions

1. ✅ **Version stability confirmed** (see Bytecode Stability section above)
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
