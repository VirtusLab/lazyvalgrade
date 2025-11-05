package lazyvalgrade.lazyval

import lazyvalgrade.classfile.ClassInfo

/** Result of semantic lazy val comparison.
  *
  * Answers: "Are the lazy val implementations identical?"
  */
sealed trait SemanticLazyValComparisonResult:
  def areIdentical: Boolean

object SemanticLazyValComparisonResult:
  /** Both classes have identical lazy val implementations */
  case object Identical extends SemanticLazyValComparisonResult:
    def areIdentical: Boolean = true

  /** Lazy val implementations differ */
  final case class Different(reasons: Seq[String]) extends SemanticLazyValComparisonResult:
    def areIdentical: Boolean = false

  /** Both classes have no lazy vals (trivially identical) */
  case object BothNoLazyVals extends SemanticLazyValComparisonResult:
    def areIdentical: Boolean = true

  /** Only one class has lazy vals (different) */
  final case class OnlyOneHasLazyVals(firstHas: Boolean, count: Int)
      extends SemanticLazyValComparisonResult:
    def areIdentical: Boolean = false

/** Semantic comparator for lazy val implementations.
  *
  * Compares lazy vals based on their implementation patterns,
  * ignoring all other bytecode differences.
  *
  * Version-aware comparison:
  * - 3.0.x/3.1.x use same pattern (bitmap-based with getOffset)
  * - 3.2.x uses similar bitmap pattern (with getDeclaredField)
  * - 3.3-3.7.x use same pattern (object-based with Unsafe)
  * - 3.8+ uses new pattern (object-based with VarHandle)
  */
final class SemanticLazyValComparator:
  import scribe.{debug, trace}

  /** Compares lazy val implementations semantically.
    *
    * @param class1 First class
    * @param class2 Second class
    * @return Whether lazy val implementations are identical
    */
  def compare(
      class1: ClassInfo,
      class2: ClassInfo,
      companion1: Option[ClassInfo] = None,
      companion2: Option[ClassInfo] = None
  ): SemanticLazyValComparisonResult =
    debug(s"Semantic comparison of lazy vals: ${class1.name} vs ${class2.name}")
    companion1.foreach(c => debug(s"  class1 companion: ${c.name}"))
    companion2.foreach(c => debug(s"  class2 companion: ${c.name}"))

    // Detect lazy vals in both classes
    val detector = LazyValDetector()
    val result1 = detector.detect(class1, companion1)
    val result2 = detector.detect(class2, companion2)

    (result1, result2) match
      case (LazyValDetectionResult.NoLazyVals, LazyValDetectionResult.NoLazyVals) =>
        debug("Both classes have no lazy vals")
        SemanticLazyValComparisonResult.BothNoLazyVals

      case (LazyValDetectionResult.NoLazyVals, _) =>
        val count = extractLazyVals(result2).size
        debug(s"Only second class has lazy vals ($count)")
        SemanticLazyValComparisonResult.OnlyOneHasLazyVals(firstHas = false, count)

      case (_, LazyValDetectionResult.NoLazyVals) =>
        val count = extractLazyVals(result1).size
        debug(s"Only first class has lazy vals ($count)")
        SemanticLazyValComparisonResult.OnlyOneHasLazyVals(firstHas = true, count)

      case _ =>
        val lazyVals1 = extractLazyVals(result1)
        val lazyVals2 = extractLazyVals(result2)
        compareImplementations(lazyVals1, lazyVals2)

  /** Extracts lazy vals from detection result. */
  private def extractLazyVals(result: LazyValDetectionResult): Seq[LazyValInfo] =
    result match
      case LazyValDetectionResult.NoLazyVals                  => Seq.empty
      case LazyValDetectionResult.LazyValsFound(lazyVals, _)  => lazyVals
      case LazyValDetectionResult.MixedVersions(lazyVals)     => lazyVals

  /** Compares two sequences of lazy vals semantically. */
  private def compareImplementations(
      lazyVals1: Seq[LazyValInfo],
      lazyVals2: Seq[LazyValInfo]
  ): SemanticLazyValComparisonResult =
    val reasons = scala.collection.mutable.ArrayBuffer[String]()

    // Create maps keyed by lazy val name (primary identity)
    val map1 = lazyVals1.map(lv => lv.name -> lv).toMap
    val map2 = lazyVals2.map(lv => lv.name -> lv).toMap

    // Check for lazy vals only in one class
    val onlyIn1 = map1.keySet -- map2.keySet
    val onlyIn2 = map2.keySet -- map1.keySet

    if onlyIn1.nonEmpty then
      reasons += s"Lazy vals only in first: ${onlyIn1.mkString(", ")}"

    if onlyIn2.nonEmpty then
      reasons += s"Lazy vals only in second: ${onlyIn2.mkString(", ")}"

    // Compare lazy vals present in both
    val common = map1.keySet.intersect(map2.keySet)
    trace(s"Comparing ${common.size} common lazy vals")

    for name <- common.toSeq.sorted do
      val lv1 = map1(name)
      val lv2 = map2(name)

      // Extract canonical pattern for each
      val pattern1 = extractCanonicalPattern(lv1)
      val pattern2 = extractCanonicalPattern(lv2)

      if pattern1 != pattern2 then
        val diff = describePatternDifference(name, pattern1, pattern2)
        reasons += diff
        trace(s"Lazy val $name differs: $diff")
      else
        trace(s"Lazy val $name: identical pattern")

    if reasons.isEmpty then
      debug("All lazy val implementations are identical")
      SemanticLazyValComparisonResult.Identical
    else
      debug(s"Lazy val implementations differ: ${reasons.size} reasons")
      SemanticLazyValComparisonResult.Different(reasons.toSeq)

  /** Extracts a canonical pattern signature for a lazy val.
    *
    * This captures the essential implementation characteristics
    * for the detected version.
    */
  private def extractCanonicalPattern(lv: LazyValInfo): LazyValCanonicalPattern =
    lv.version match
      case ScalaVersion.Scala30x_31x | ScalaVersion.Scala32x =>
        // Bitmap-based inline pattern
        LazyValCanonicalPattern.BitmapBased(
          hasOffsetField = lv.offsetField.isDefined,
          hasBitmapField = lv.bitmapField.isDefined,
          storageDescriptor = lv.storageField.descriptor,
          hasInitMethod = lv.initMethod.isDefined, // Should be false
          accessorInstructionCount = lv.accessorMethod.map(_.instructions.size)
        )

      case ScalaVersion.Scala33x_37x =>
        // Object-based with Unsafe
        LazyValCanonicalPattern.ObjectBasedUnsafe(
          hasOffsetField = lv.offsetField.isDefined,
          storageDescriptor = lv.storageField.descriptor,
          hasInitMethod = lv.initMethod.isDefined,
          initInstructionCount = lv.initMethod.map(_.instructions.size),
          accessorInstructionCount = lv.accessorMethod.map(_.instructions.size)
        )

      case ScalaVersion.Scala38Plus =>
        // Object-based with VarHandle
        LazyValCanonicalPattern.ObjectBasedVarHandle(
          hasVarHandleField = lv.varHandleField.isDefined,
          storageDescriptor = lv.storageField.descriptor,
          hasInitMethod = lv.initMethod.isDefined,
          initInstructionCount = lv.initMethod.map(_.instructions.size),
          accessorInstructionCount = lv.accessorMethod.map(_.instructions.size)
        )

      case ScalaVersion.Unknown =>
        LazyValCanonicalPattern.Unknown

  /** Describes the difference between two patterns. */
  private def describePatternDifference(
      name: String,
      p1: LazyValCanonicalPattern,
      p2: LazyValCanonicalPattern
  ): String =
    (p1, p2) match
      case (LazyValCanonicalPattern.BitmapBased(_, _, desc1, _, acc1),
            LazyValCanonicalPattern.BitmapBased(_, _, desc2, _, acc2)) =>
        if desc1 != desc2 then
          s"Lazy val '$name': storage type differs ($desc1 vs $desc2)"
        else if acc1 != acc2 then
          s"Lazy val '$name': accessor differs ($acc1 vs $acc2 instructions)"
        else
          s"Lazy val '$name': bitmap-based patterns differ in structure"

      case (LazyValCanonicalPattern.ObjectBasedUnsafe(_, desc1, _, init1, acc1),
            LazyValCanonicalPattern.ObjectBasedUnsafe(_, desc2, _, init2, acc2)) =>
        if desc1 != desc2 then
          s"Lazy val '$name': storage type differs ($desc1 vs $desc2)"
        else if init1 != init2 then
          s"Lazy val '$name': init method differs ($init1 vs $init2 instructions)"
        else if acc1 != acc2 then
          s"Lazy val '$name': accessor differs ($acc1 vs $acc2 instructions)"
        else
          s"Lazy val '$name': Unsafe-based patterns differ in structure"

      case (LazyValCanonicalPattern.ObjectBasedVarHandle(_, desc1, _, init1, acc1),
            LazyValCanonicalPattern.ObjectBasedVarHandle(_, desc2, _, init2, acc2)) =>
        if desc1 != desc2 then
          s"Lazy val '$name': storage type differs ($desc1 vs $desc2)"
        else if init1 != init2 then
          s"Lazy val '$name': init method differs ($init1 vs $init2 instructions)"
        else if acc1 != acc2 then
          s"Lazy val '$name': accessor differs ($acc1 vs $acc2 instructions)"
        else
          s"Lazy val '$name': VarHandle-based patterns differ in structure"

      case _ =>
        s"Lazy val '$name': different implementation versions ($p1 vs $p2)"

/** Canonical pattern signature for a lazy val implementation. */
private sealed trait LazyValCanonicalPattern

private object LazyValCanonicalPattern:
  /** Bitmap-based implementation (3.0-3.2) */
  final case class BitmapBased(
      hasOffsetField: Boolean,
      hasBitmapField: Boolean,
      storageDescriptor: String,
      hasInitMethod: Boolean,
      accessorInstructionCount: Option[Int]
  ) extends LazyValCanonicalPattern

  /** Object-based with Unsafe (3.3-3.7) */
  final case class ObjectBasedUnsafe(
      hasOffsetField: Boolean,
      storageDescriptor: String,
      hasInitMethod: Boolean,
      initInstructionCount: Option[Int],
      accessorInstructionCount: Option[Int]
  ) extends LazyValCanonicalPattern

  /** Object-based with VarHandle (3.8+) */
  final case class ObjectBasedVarHandle(
      hasVarHandleField: Boolean,
      storageDescriptor: String,
      hasInitMethod: Boolean,
      initInstructionCount: Option[Int],
      accessorInstructionCount: Option[Int]
  ) extends LazyValCanonicalPattern

  /** Unknown pattern */
  case object Unknown extends LazyValCanonicalPattern

object SemanticLazyValComparator:
  /** Creates a new semantic comparator instance. */
  def apply(): SemanticLazyValComparator = new SemanticLazyValComparator()

  /** Convenience method to compare lazy vals semantically. */
  def compare(
      class1: ClassInfo,
      class2: ClassInfo,
      companion1: Option[ClassInfo] = None,
      companion2: Option[ClassInfo] = None
  ): SemanticLazyValComparisonResult =
    SemanticLazyValComparator().compare(class1, class2, companion1, companion2)
