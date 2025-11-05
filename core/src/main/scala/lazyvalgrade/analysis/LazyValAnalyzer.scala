package lazyvalgrade.analysis

import lazyvalgrade.classfile.ClassfileParser
import scala.collection.mutable

/** Analyzes classfiles to discover lazy val structures and companion relationships.
  *
  * Groups classfiles into ClassfileGroup instances, identifying companion object/class pairs where lazy val
  * implementation may be split across both files.
  */
object LazyValAnalyzer {

  /** Groups classfiles by identifying companion relationships.
    *
    * When a companion object (Foo$) and class (Foo) both exist, they are grouped as CompanionPair. Otherwise,
    * classfiles are treated as Single groups.
    *
    * @param classfiles
    *   Map of className -> bytes
    * @return
    *   Sequence of ClassfileGroup instances
    */
  def group(classfiles: Map[String, Array[Byte]]): Either[String, Seq[ClassfileGroup]] = {
    val parsed = mutable.Map[String, (lazyvalgrade.classfile.ClassInfo, Array[Byte])]()
    val errors = mutable.ArrayBuffer[String]()

    // Parse all classfiles
    for ((name, bytes) <- classfiles) {
      ClassfileParser.parse(bytes) match {
        case Right(classInfo) =>
          parsed(name) = (classInfo, bytes)
        case Left(error) =>
          errors += s"Failed to parse $name: $error"
      }
    }

    if (errors.nonEmpty) {
      return Left(errors.mkString("\n"))
    }

    val groups = mutable.ArrayBuffer[ClassfileGroup]()
    val processed = mutable.Set[String]()

    // Process companion pairs first
    for ((companionObjectName, (companionObjectInfo, companionObjectBytes)) <- parsed) {
      if (companionObjectName.endsWith("$") && !processed.contains(companionObjectName)) {
        val className = companionObjectName.stripSuffix("$")

        parsed.get(className) match {
          case Some((classInfo, classBytes)) =>
            // Found companion pair
            groups += ClassfileGroup.CompanionPair(
              companionObjectName = companionObjectName,
              className = className,
              companionObjectInfo = companionObjectInfo,
              classInfo = classInfo,
              companionObjectBytes = companionObjectBytes,
              classBytes = classBytes
            )
            processed += companionObjectName
            processed += className

          case None =>
            // Companion object without companion class - treat as single
            groups += ClassfileGroup.Single(
              name = companionObjectName,
              classInfo = companionObjectInfo,
              bytes = companionObjectBytes
            )
            processed += companionObjectName
        }
      }
    }

    // Process remaining singles (non-companion objects and classes without companions)
    for ((name, (classInfo, bytes)) <- parsed) {
      if (!processed.contains(name)) {
        groups += ClassfileGroup.Single(
          name = name,
          classInfo = classInfo,
          bytes = bytes
        )
      }
    }

    Right(groups.toSeq)
  }

  /** Convenience method to analyze classfiles from a directory path.
    *
    * @param classfiles
    *   Sequence of (name, bytes) pairs
    * @return
    *   Either error message or sequence of ClassfileGroup
    */
  def groupFromPaths(classfiles: Seq[(String, Array[Byte])]): Either[String, Seq[ClassfileGroup]] = {
    group(classfiles.toMap)
  }
}
