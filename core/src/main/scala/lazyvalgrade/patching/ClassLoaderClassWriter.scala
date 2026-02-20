package lazyvalgrade.patching

import org.objectweb.asm.{ClassReader, ClassWriter, Opcodes}

/** ClassWriter subclass that resolves class hierarchies WITHOUT triggering class loading.
  *
  * ASM's COMPUTE_FRAMES needs to find common superclasses. The default implementation uses
  * Class.forName() which triggers JVM class loading — problematic in agent mode because it
  * can load classes with unpatched bytes before the transformer has a chance to patch them.
  *
  * This implementation reads class bytecode via getResourceAsStream and parses superclass
  * names with ASM's ClassReader, walking the hierarchy without ever calling loadClass().
  */
class ClassLoaderClassWriter(classLoader: ClassLoader) extends ClassWriter(ClassWriter.COMPUTE_FRAMES):

  private case class ClassInfo(superName: String, interfaces: Array[String], isInterface: Boolean)

  private def readClassInfo(internalName: String): Option[ClassInfo] =
    if internalName == "java/lang/Object" then
      Some(ClassInfo(null, Array.empty, isInterface = false))
    else
      val resource = classLoader.getResourceAsStream(internalName + ".class")
      if resource == null then None
      else
        try
          val reader = new ClassReader(resource)
          val isInterface = (reader.getAccess & Opcodes.ACC_INTERFACE) != 0
          Some(ClassInfo(reader.getSuperName, reader.getInterfaces, isInterface))
        catch case _: Throwable => None
        finally resource.close()

  private def getSuperClasses(internalName: String): List[String] =
    var result = List(internalName)
    var current = internalName
    while current != null && current != "java/lang/Object" do
      readClassInfo(current) match
        case Some(info) if info.superName != null =>
          result = info.superName :: result
          current = info.superName
        case _ =>
          current = null
    result

  private def isAssignableFrom(target: String, source: String): Boolean =
    if target == source then return true
    if target == "java/lang/Object" then return true
    // Walk source's hierarchy checking superclasses and interfaces
    var queue = List(source)
    var visited = Set.empty[String]
    while queue.nonEmpty do
      val current = queue.head
      queue = queue.tail
      if current == target then return true
      if !visited.contains(current) then
        visited += current
        readClassInfo(current) match
          case Some(info) =>
            if info.superName != null then queue = info.superName :: queue
            queue = info.interfaces.toList ::: queue
          case None => // can't resolve, skip
    false

  override def getCommonSuperClass(type1: String, type2: String): String =
    if type1 == "java/lang/Object" || type2 == "java/lang/Object" then
      return "java/lang/Object"

    val info1 = readClassInfo(type1)
    val info2 = readClassInfo(type2)

    // If we can't read either class, fall back to Object
    if info1.isEmpty || info2.isEmpty then return "java/lang/Object"

    if isAssignableFrom(type1, type2) then type1
    else if isAssignableFrom(type2, type1) then type2
    else if info1.get.isInterface || info2.get.isInterface then "java/lang/Object"
    else
      // Walk type1's superclass chain until we find one that type2 is assignable to
      val supers1 = getSuperClasses(type1)
      // supers1 is in root-first order, so iterate from the end (most specific first)
      supers1.reverse.find(s => isAssignableFrom(s, type2)).getOrElse("java/lang/Object")
