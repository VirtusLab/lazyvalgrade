# Refactoring: Companion Class/Object Support

## Overview
Refactored lazy val detection and patching to handle companion class/object pairs where OFFSET fields may be split across the companion class while lazy val implementation lives in the companion object.

## Key Structural Changes

### 1. LazyValDetector API Change
- **Before**: `detect(classInfo: ClassInfo)`
- **After**: `detect(classInfo: ClassInfo, companionClassInfo: Option[ClassInfo])`
- Now accepts optional companion class for cross-class field analysis

### 2. New OffsetFieldLocation Enum
```scala
enum OffsetFieldLocation:
  case InSameClass        // OFFSET in same class as lazy val
  case InCompanionClass   // OFFSET in companion class, lazy val in object
  case NoOffsetField      // No OFFSET (3.8+ VarHandle)
```
Tracks where OFFSET fields are located in companion scenarios.

### 3. Enhanced Offset Mapping
- `buildOffsetMapping` now searches `<clinit>` in **both** current class and companion class
- Extracts helper `parseClinitMethod` for reuse across multiple classes
- Maps OFFSET fields regardless of which class contains them

### 4. Field Search Across Classes
- `findOffsetFieldForStorage` returns tuple: `(Option[FieldInfo], OffsetFieldLocation)`
- Searches current class first, then companion class
- `findBitmapFieldForStorage` receives combined field list from both classes

### 5. LazyValInfo Model Update
```scala
final case class LazyValInfo(
  // ... existing fields ...
  offsetFieldLocation: OffsetFieldLocation,  // NEW
  // ...
)
```

### 6. BytecodePatcher Redesign
- **Before**: `patch(bytes: Array[Byte])`
- **After**: `patch(group: ClassfileGroup)`
- Now works with ClassfileGroup (Single | CompanionPair)
- Returns PatchedSingle or PatchedPair based on input

### 7. PatchResult Variants
```scala
sealed trait PatchResult
case class PatchedSingle(name: String, bytes: Array[Byte])
case class PatchedPair(
  companionObjectName: String,
  className: String,
  companionObjectBytes: Array[Byte],
  classBytes: Array[Byte]
)
```

### 8. Companion Class Patching (3.3-3.7)
- New `patchCompanionClass33x_37x` helper
- Removes OFFSET$_m_N fields from companion class
- Removes <clinit> method from companion class
- Returns both patched object and class bytes

## Problem Addressed
Companion objects with lazy vals in Scala 3.x store:
- **Lazy val implementation** (storage field, accessor, initialization) in companion object `Foo$`
- **OFFSET field** (for memory offset calculation) in companion class `Foo`
- **<clinit> initialization** (calculates OFFSET) in companion class `Foo`

Previous implementation only analyzed single classfiles and couldn't detect this split configuration.

## Impact
- Detection now correctly identifies lazy vals in companion object scenarios across all Scala 3.x versions
- Patching can transform both companion object and class when needed (3.3-3.7)
- Maintains backward compatibility with standalone objects/classes
