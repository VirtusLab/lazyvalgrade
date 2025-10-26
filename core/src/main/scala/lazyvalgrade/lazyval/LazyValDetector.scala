package lazyvalgrade.lazyval

import lazyvalgrade.classfile.{ClassInfo, FieldInfo, MethodInfo}
import org.objectweb.asm.Opcodes
import scala.util.matching.Regex

/** Detects and classifies lazy vals in Scala 3 classfiles.
  *
  * Identifies lazy val implementation patterns from Scala 3.0 through 3.8+
  * and classifies them by version family.
  */
final class LazyValDetector:
  import scribe.{info, debug, warn}
  import LazyValDetector._

  /** Detects lazy vals in a classfile and classifies their implementation version.
    *
    * @param classInfo The parsed classfile
    * @return Detection result indicating if lazy vals were found and their versions
    */
  def detect(classInfo: ClassInfo): LazyValDetectionResult =
    debug(s"Detecting lazy vals in class ${classInfo.name}")

    // Step 1: Find all potential lazy val fields
    val storageFields = findStorageFields(classInfo.fields)

    if storageFields.isEmpty then
      debug("No lazy val storage fields found")
      return LazyValDetectionResult.NoLazyVals

    info(s"Found ${storageFields.size} potential lazy val storage fields")

    // Step 2: Parse <clinit> to build offset->storageField mapping
    val offsetMapping = buildOffsetMapping(classInfo)
    debug(s"Built offset mapping: $offsetMapping")

    // Step 3: Build LazyValInfo for each detected lazy val
    val lazyVals = storageFields.flatMap { storageField =>
      buildLazyValInfo(storageField, classInfo, offsetMapping)
    }

    if lazyVals.isEmpty then
      debug("Could not build LazyValInfo for any storage fields")
      return LazyValDetectionResult.NoLazyVals

    info(s"Successfully identified ${lazyVals.size} lazy vals")

    // Step 4: Determine overall version
    val versions = lazyVals.map(_.version).distinct

    if versions.size == 1 then
      LazyValDetectionResult.LazyValsFound(lazyVals, versions.head)
    else
      warn(s"Mixed versions detected: $versions")
      LazyValDetectionResult.MixedVersions(lazyVals)

  /** Builds a mapping from OFFSET field names to lazy val storage field names.
    *
    * Parses <clinit> bytecode to find LDC instructions that load field names
    * before PUTSTATIC OFFSET$_m_<N> instructions.
    *
    * Returns Map[offsetFieldName -> storageFieldName]
    */
  private def buildOffsetMapping(classInfo: ClassInfo): Map[String, String] =
    val clinit = classInfo.methods.find(_.name == "<clinit>")

    clinit match
      case None =>
        debug("No <clinit> method found")
        Map.empty

      case Some(method) =>
        val bytecode = method.bytecodeText
        val mapping = scala.collection.mutable.Map[String, String]()

        // Pattern: LDC "fieldName" followed by getDeclaredField/getOffset, then PUTSTATIC OFFSET$_m_<N>
        // or: LDC "fieldName" ... PUTSTATIC <name>$lzy<N>  (for bitmap-based)
        val lines = bytecode.split("\n")

        var lastLdcField: Option[String] = None

        lines.foreach { line =>
          val trimmed = line.trim

          // Check for LDC with a field name containing "$lzy"
          if trimmed.startsWith("LDC \"") && trimmed.contains("$lzy") then
            val fieldNamePattern = """LDC "([^"]+\$lzy\d+)"""".r
            fieldNamePattern.findFirstMatchIn(trimmed).foreach { m =>
              lastLdcField = Some(m.group(1))
              debug(s"Found LDC for field: ${m.group(1)}")
            }

          // Check for LDC with a bitmap field name
          else if trimmed.startsWith("LDC \"") && trimmed.contains("bitmap$") then
            val bitmapPattern = """LDC "(\d+bitmap\$\d+)"""".r
            bitmapPattern.findFirstMatchIn(trimmed).foreach { m =>
              lastLdcField = Some(m.group(1))
              debug(s"Found LDC for bitmap field: ${m.group(1)}")
            }

          // Check for PUTSTATIC OFFSET$_m_<N>
          else if trimmed.contains("PUTSTATIC") && trimmed.contains("OFFSET$_m_") then
            val offsetPattern = """OFFSET\$_m_(\d+)""".r
            offsetPattern.findFirstMatchIn(trimmed).foreach { m =>
              val offsetField = s"OFFSET$$_m_${m.group(1)}"
              lastLdcField.foreach { storageField =>
                mapping(offsetField) = storageField
                debug(s"Mapped $offsetField -> $storageField")
              }
              lastLdcField = None
            }
        }

        mapping.toMap

  /** Finds all lazy val storage fields in the class.
    *
    * Storage fields follow the pattern: <name>$lzy<N>
    */
  private def findStorageFields(fields: Seq[FieldInfo]): Seq[FieldInfo] =
    fields.filter { field =>
      storageFieldPattern.matches(field.name) &&
      !field.name.endsWith("$lzyHandle") // Exclude VarHandle fields
    }

  /** Builds complete LazyValInfo for a storage field. */
  private def buildLazyValInfo(
      storageField: FieldInfo,
      classInfo: ClassInfo,
      offsetMapping: Map[String, String]
  ): Option[LazyValInfo] =
    val nameAndIndex = parseStorageFieldName(storageField.name)

    nameAndIndex.flatMap { case (name, index) =>
      // Find associated fields using the offset mapping
      val offsetField = findOffsetFieldForStorage(storageField.name, offsetMapping, classInfo.fields)
      val bitmapField = findBitmapFieldForStorage(storageField.name, offsetMapping, classInfo.fields)
      val varHandleField = findVarHandleField(name, index, classInfo.fields)

      // Find associated methods
      val initMethod = findInitMethod(name, index, classInfo.methods)
      val accessorMethod = findAccessorMethod(name, classInfo.methods)

      // Determine version based on field and method patterns
      val version = determineVersion(
        storageField,
        offsetField,
        bitmapField,
        varHandleField,
        initMethod,
        accessorMethod,
        classInfo
      )

      Some(LazyValInfo(
        name = name,
        index = index,
        offsetField = offsetField,
        bitmapField = bitmapField,
        storageField = storageField,
        varHandleField = varHandleField,
        initMethod = initMethod,
        accessorMethod = accessorMethod,
        version = version
      ))
    }

  /** Parses storage field name to extract lazy val name and index.
    *
    * Example: "simpleLazy$lzy1" -> ("simpleLazy", 1)
    */
  private def parseStorageFieldName(fieldName: String): Option[(String, Int)] =
    storageFieldPattern.findFirstMatchIn(fieldName).map { m =>
      val name = m.group(1)
      val index = m.group(2).toInt
      (name, index)
    }

  /** Finds the OFFSET field that corresponds to a storage field.
    *
    * Uses the offset mapping built from <clinit> bytecode to find the correct OFFSET field.
    */
  private def findOffsetFieldForStorage(
      storageFieldName: String,
      offsetMapping: Map[String, String],
      fields: Seq[FieldInfo]
  ): Option[FieldInfo] =
    // Find which OFFSET field maps to this storage field
    val offsetFieldName = offsetMapping.collectFirst {
      case (offsetName, mappedStorageName) if mappedStorageName == storageFieldName => offsetName
    }

    offsetFieldName.flatMap { offsetName =>
      fields.find { field =>
        field.name == offsetName &&
        field.descriptor == "J" && // long type
        isStaticFinal(field.access)
      }
    }

  /** Finds the bitmap field that corresponds to a storage field.
    *
    * For 3.0.x-3.2.x, the bitmap field is referenced in <clinit> instead of the lzy field.
    */
  private def findBitmapFieldForStorage(
      storageFieldName: String,
      offsetMapping: Map[String, String],
      fields: Seq[FieldInfo]
  ): Option[FieldInfo] =
    // Find which OFFSET field maps to a bitmap field
    val bitmapFieldName = offsetMapping.collectFirst {
      case (offsetName, mappedName) if mappedName.contains("bitmap$") => mappedName
    }

    bitmapFieldName.flatMap { bitmapName =>
      fields.find { field =>
        field.name == bitmapName &&
        field.descriptor == "J" && // long type
        !isStatic(field.access) // Instance field
      }
    }

  /** Finds the VarHandle field <name>$lzy<N>$lzyHandle. */
  private def findVarHandleField(
      name: String,
      index: Int,
      fields: Seq[FieldInfo]
  ): Option[FieldInfo] =
    fields.find { field =>
      field.name == s"$name$$lzy$index$$lzyHandle" &&
      field.descriptor == "Ljava/lang/invoke/VarHandle;" &&
      isStaticFinal(field.access) &&
      isPrivate(field.access)
    }

  /** Finds the initialization method <name>$lzyINIT<N>(). */
  private def findInitMethod(
      name: String,
      index: Int,
      methods: Seq[MethodInfo]
  ): Option[MethodInfo] =
    methods.find { method =>
      method.name == s"$name$$lzyINIT$index" &&
      method.descriptor == "()Ljava/lang/Object;"
    }

  /** Finds the accessor method <name>(). */
  private def findAccessorMethod(
      name: String,
      methods: Seq[MethodInfo]
  ): Option[MethodInfo] =
    methods.find(_.name == name)

  /** Determines the Scala version based on field and method patterns. */
  private def determineVersion(
      storageField: FieldInfo,
      offsetField: Option[FieldInfo],
      bitmapField: Option[FieldInfo],
      varHandleField: Option[FieldInfo],
      initMethod: Option[MethodInfo],
      accessorMethod: Option[MethodInfo],
      classInfo: ClassInfo
  ): ScalaVersion =
    // First, check for VarHandle (3.8+)
    if varHandleField.isDefined then
      debug("VarHandle field found -> Scala 3.8+")
      return ScalaVersion.Scala38Plus

    // Check for bitmap field (3.0-3.2.x)
    if bitmapField.isDefined then
      debug("Bitmap field found -> Scala 3.0.x-3.2.x")
      // Distinguish between 3.0.x/3.1.x and 3.2.x by checking <clinit>
      val clinit = classInfo.methods.find(_.name == "<clinit>")
      clinit match
        case Some(method) =>
          val bytecode = method.bytecodeText
          if bytecode.contains("getDeclaredField") &&
             bytecode.contains("getOffsetStatic") then
            debug("Uses getDeclaredField + getOffsetStatic -> Scala 3.2.x")
            ScalaVersion.Scala32x
          else if bytecode.contains("LazyVals$.getOffset (") then
            debug("Uses LazyVals$.getOffset -> Scala 3.0.x-3.1.x")
            ScalaVersion.Scala30x_31x
          else
            warn("Bitmap field present but unrecognized <clinit> pattern")
            ScalaVersion.Unknown
        case None =>
          warn("Bitmap field present but no <clinit> method found")
          ScalaVersion.Unknown

    // Check for Object-based with OFFSET (3.3-3.7.x)
    else if offsetField.isDefined &&
             initMethod.isDefined &&
             storageField.descriptor == "Ljava/lang/Object;" &&
             isVolatile(storageField.access) then
      // Verify by checking for objCAS usage
      initMethod match
        case Some(method) =>
          if method.bytecodeText.contains("LazyVals$.objCAS") then
            debug("Uses objCAS with Object field -> Scala 3.3.x-3.7.x")
            ScalaVersion.Scala33x_37x
          else
            warn("Object field with OFFSET but no objCAS found")
            ScalaVersion.Unknown
        case None =>
          ScalaVersion.Unknown

    else
      warn(s"Could not determine version for field ${storageField.name}")
      warn(s"  offsetField: ${offsetField.isDefined}")
      warn(s"  bitmapField: ${bitmapField.isDefined}")
      warn(s"  varHandleField: ${varHandleField.isDefined}")
      warn(s"  initMethod: ${initMethod.isDefined}")
      warn(s"  descriptor: ${storageField.descriptor}")
      ScalaVersion.Unknown

  /** Checks if access flags indicate a static field. */
  private def isStatic(access: Int): Boolean =
    (access & Opcodes.ACC_STATIC) != 0

  /** Checks if access flags indicate a final field. */
  private def isFinal(access: Int): Boolean =
    (access & Opcodes.ACC_FINAL) != 0

  /** Checks if access flags indicate a static final field. */
  private def isStaticFinal(access: Int): Boolean =
    isStatic(access) && isFinal(access)

  /** Checks if access flags indicate a volatile field. */
  private def isVolatile(access: Int): Boolean =
    (access & Opcodes.ACC_VOLATILE) != 0

  /** Checks if access flags indicate a private member. */
  private def isPrivate(access: Int): Boolean =
    (access & Opcodes.ACC_PRIVATE) != 0

object LazyValDetector:
  /** Pattern for lazy val storage fields: <name>$lzy<N> */
  private val storageFieldPattern: Regex = """^(.+)\$lzy(\d+)$""".r

  /** Creates a new detector instance. */
  def apply(): LazyValDetector = new LazyValDetector()

  /** Convenience method to detect lazy vals in a classfile. */
  def detect(classInfo: ClassInfo): LazyValDetectionResult =
    LazyValDetector().detect(classInfo)
