package lazyvalgrade.analysis

import lazyvalgrade.classfile.ClassInfo

/** Represents a group of related classfiles for lazy val analysis.
  *
  * Lazy vals can be implemented across a single class or split between a companion object and its class.
  */
sealed trait ClassfileGroup {
  def primaryName: String
}

object ClassfileGroup {

  /** A single classfile containing lazy vals.
    *
    * Used for:
    *   - Objects without companion classes (e.g., SimpleLazyVal$)
    *   - Classes with lazy vals in instance fields
    */
  case class Single(
      name: String,
      classInfo: ClassInfo,
      bytes: Array[Byte]
  ) extends ClassfileGroup {
    def primaryName: String = name
  }

  /** A companion object paired with its class.
    *
    * Used when lazy vals are split between the companion class and object:
    *   - Companion class (e.g., Foo) contains OFFSET$ fields
    *   - Companion object (e.g., Foo$) contains lazy val implementation
    *
    * This happens with `object Foo: lazy val x = ...` when there's also a `class Foo` defined.
    */
  case class CompanionPair(
      companionObjectName: String,
      className: String,
      companionObjectInfo: ClassInfo,
      classInfo: ClassInfo,
      companionObjectBytes: Array[Byte],
      classBytes: Array[Byte]
  ) extends ClassfileGroup {
    def primaryName: String = companionObjectName
  }
}
