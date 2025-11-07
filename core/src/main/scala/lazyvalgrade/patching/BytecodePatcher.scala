package lazyvalgrade.patching

import org.objectweb.asm.*
import org.objectweb.asm.tree.*
import org.objectweb.asm.Opcodes.*
import scala.jdk.CollectionConverters.*
import lazyvalgrade.classfile.ClassfileParser
import lazyvalgrade.lazyval.{LazyValDetector, LazyValInfo, LazyValDetectionResult, ScalaVersion, OffsetFieldLocation}
import lazyvalgrade.analysis.ClassfileGroup

/** Patches Scala 3.x lazy val bytecode to Scala 3.8+ format.
  *
  * Supports multiple lazy val implementation variants:
  *   - Scala 3.0-3.1: Field-based with bitmap (not yet implemented)
  *   - Scala 3.2: VarHandle-based with different initialization pattern (not yet implemented)
  *   - Scala 3.3-3.7: Unsafe-based → VarHandle transformation (implemented)
  *
  * The 3.3-3.7 transformation:
  *   - Field level: OFFSET$_m_N (long) → <name>$lzyN$lzyHandle (VarHandle)
  *   - Static initializer: Unsafe offset lookup → VarHandle findVarHandle
  *   - lzyINIT methods: LazyVals$.objCAS → VarHandle.compareAndSet
  *   - Inner classes: Add MethodHandles$Lookup reference
  */
object BytecodePatcher {

  /** Result of a patching operation */
  sealed trait PatchResult
  object PatchResult {
    /** Single classfile was patched */
    case class PatchedSingle(name: String, bytes: Array[Byte]) extends PatchResult

    /** Companion pair was patched (both object and class) */
    case class PatchedPair(
        companionObjectName: String,
        className: String,
        companionObjectBytes: Array[Byte],
        classBytes: Array[Byte]
    ) extends PatchResult

    /** No patching needed (already 3.8+ or no lazy vals) */
    case object NotApplicable extends PatchResult

    /** Patching failed with error */
    final case class Failed(error: String) extends PatchResult
  }

  /** Patches a classfile group to 3.8+ format, handling both single files and companion pairs.
    *
    * @param group
    *   The classfile group (single or companion pair)
    * @return
    *   PatchResult indicating success, failure, or not applicable
    */
  def patch(group: ClassfileGroup): PatchResult = group match {
    case ClassfileGroup.Single(name, classInfo, bytes) =>
      // Detect lazy vals in single class (no companion)
      val detectionResult = LazyValDetector.detect(classInfo, None)

      val (lazyVals, version) = detectionResult match {
        case LazyValDetectionResult.NoLazyVals => return PatchResult.NotApplicable
        case LazyValDetectionResult.LazyValsFound(lvs, ver) => (lvs, ver)
        case LazyValDetectionResult.MixedVersions(_) => return PatchResult.Failed("Mixed Scala versions detected")
      }

      // Dispatch to version-specific patching
      version match {
        case ScalaVersion.Scala30x_31x => patchScala30x_31x(bytes, classInfo, lazyVals, name)
        case ScalaVersion.Scala32x => patchScala32x(bytes, classInfo, lazyVals, name)
        case ScalaVersion.Scala33x_37x => patchScala33x_37x(bytes, classInfo, lazyVals, name, None, None)
        case ScalaVersion.Scala38Plus => PatchResult.NotApplicable
        case ScalaVersion.Unknown => PatchResult.Failed("Unknown Scala version detected")
      }

    case ClassfileGroup.CompanionPair(
          companionObjectName,
          className,
          companionObjectInfo,
          classInfo,
          companionObjectBytes,
          classBytes
        ) =>
      // Detect lazy vals in companion object with companion class context
      val objectDetectionResult = LazyValDetector.detect(companionObjectInfo, Some(classInfo))

      // Also detect lazy vals in companion class (standalone detection)
      val classDetectionResult = LazyValDetector.detect(classInfo, None)

      // Determine if we need to patch
      val (objectLazyVals, classLazyVals, version) = (objectDetectionResult, classDetectionResult) match {
        case (LazyValDetectionResult.NoLazyVals, LazyValDetectionResult.NoLazyVals) =>
          return PatchResult.NotApplicable
        case (LazyValDetectionResult.LazyValsFound(objLvs, objVer), LazyValDetectionResult.NoLazyVals) =>
          (objLvs, Seq.empty, objVer)
        case (LazyValDetectionResult.NoLazyVals, LazyValDetectionResult.LazyValsFound(clsLvs, clsVer)) =>
          (Seq.empty, clsLvs, clsVer)
        case (LazyValDetectionResult.LazyValsFound(objLvs, objVer), LazyValDetectionResult.LazyValsFound(clsLvs, clsVer)) =>
          // Both have lazy vals - versions must match
          if (objVer != clsVer) {
            return PatchResult.Failed(s"Companion class and object have different Scala versions: $clsVer vs $objVer")
          }
          (objLvs, clsLvs, objVer)
        case (LazyValDetectionResult.MixedVersions(_), _) | (_, LazyValDetectionResult.MixedVersions(_)) =>
          return PatchResult.Failed("Mixed Scala versions detected")
      }

      // Dispatch to version-specific patching based on what we found
      version match {
        case ScalaVersion.Scala30x_31x | ScalaVersion.Scala32x =>
          // Not implemented yet for these versions
          if (objectLazyVals.nonEmpty) patchScala30x_31x(companionObjectBytes, companionObjectInfo, objectLazyVals, companionObjectName)
          else if (classLazyVals.nonEmpty) patchScala30x_31x(classBytes, classInfo, classLazyVals, className)
          else PatchResult.NotApplicable

        case ScalaVersion.Scala33x_37x =>
          // Handle different companion pair scenarios
          (objectLazyVals.nonEmpty, classLazyVals.nonEmpty) match {
            case (true, false) =>
              // Only object has lazy vals
              val hasCompanionOffset = objectLazyVals.exists(_.offsetFieldLocation == OffsetFieldLocation.InCompanionClass)
              if (hasCompanionOffset) {
                patchScala33x_37x(
                  companionObjectBytes,
                  companionObjectInfo,
                  objectLazyVals,
                  companionObjectName,
                  Some((className, classInfo, classBytes))
                )
              } else {
                patchScala33x_37x(companionObjectBytes, companionObjectInfo, objectLazyVals, companionObjectName, None, None)
              }
            case (false, true) =>
              // Only class has lazy vals - patch as standalone
              patchScala33x_37x(classBytes, classInfo, classLazyVals, className, None, None)
            case (true, true) =>
              // BOTH have lazy vals - need to patch both independently
              patchCompanionPairBothHaveLazyVals33x_37x(
                companionObjectName, className,
                companionObjectInfo, classInfo,
                companionObjectBytes, classBytes,
                objectLazyVals, classLazyVals
              )
            case (false, false) =>
              PatchResult.NotApplicable
          }

        case ScalaVersion.Scala38Plus => PatchResult.NotApplicable
        case ScalaVersion.Unknown => PatchResult.Failed("Unknown Scala version detected")
      }
  }

  // ============================================================================
  // Scala 3.0-3.1 Patching Strategy (Field-based with bitmap)
  // ============================================================================

  /** Patches Scala 3.0-3.1 lazy vals to 3.8+ format.
    *
    * NOT YET IMPLEMENTED.
    *
    * Scala 3.0-3.1 uses a field-based approach with bitmap for initialization tracking.
    */
  private def patchScala30x_31x(
      bytes: Array[Byte],
      classInfo: lazyvalgrade.classfile.ClassInfo,
      lazyVals: Seq[LazyValInfo],
      name: String
  ): PatchResult = {
    PatchResult.Failed("Scala 3.0-3.1 lazy val patching is not yet implemented")
  }

  // ============================================================================
  // Scala 3.2 Patching Strategy (Early VarHandle variant)
  // ============================================================================

  /** Patches Scala 3.2 lazy vals to 3.8+ format.
    *
    * NOT YET IMPLEMENTED.
    *
    * Scala 3.2 uses VarHandle but with a different initialization pattern than 3.8+.
    */
  private def patchScala32x(
      bytes: Array[Byte],
      classInfo: lazyvalgrade.classfile.ClassInfo,
      lazyVals: Seq[LazyValInfo],
      name: String
  ): PatchResult = {
    PatchResult.Failed("Scala 3.2 lazy val patching is not yet implemented")
  }

  // ============================================================================
  // Scala 3.3-3.7 Patching Strategy (Unsafe → VarHandle transformation)
  // ============================================================================

  /** Patches companion pair where BOTH class and object have lazy vals.
    *
    * This is the complex case where we need to patch both files independently.
    */
  private def patchCompanionPairBothHaveLazyVals33x_37x(
      companionObjectName: String,
      className: String,
      companionObjectInfo: lazyvalgrade.classfile.ClassInfo,
      classInfo: lazyvalgrade.classfile.ClassInfo,
      companionObjectBytes: Array[Byte],
      classBytes: Array[Byte],
      objectLazyVals: Seq[LazyValInfo],
      classLazyVals: Seq[LazyValInfo]
  ): PatchResult = {
    try {
      // Check if object lazy vals have OFFSET in companion class
      val objectHasCompanionOffset = objectLazyVals.exists(_.offsetFieldLocation == OffsetFieldLocation.InCompanionClass)

      if (objectHasCompanionOffset) {
        // Complex case: object has OFFSET in companion class, AND class has its own lazy vals
        // Step 1: Patch object (which also patches class to remove OFFSET fields)
        val objectPatchResult = patchScala33x_37x(
          companionObjectBytes,
          companionObjectInfo,
          objectLazyVals,
          companionObjectName,
          Some((className, classInfo, classBytes))
        )

        objectPatchResult match {
          case PatchResult.PatchedPair(_, _, patchedObjectBytes, intermediatePatchedClassBytes) =>
            // Step 2: Parse the intermediate patched class bytes and patch again for class's own lazy vals
            val intermediateClassInfo = ClassfileParser.parse(intermediatePatchedClassBytes).toOption.get
            val reader = new ClassReader(intermediatePatchedClassBytes)
            val classNode = new ClassNode(ASM9)
            reader.accept(classNode, ClassReader.EXPAND_FRAMES)

            // Patch the class node for its own lazy vals
            patchClassNode33x_37x(classNode, className, classLazyVals)

            // Write back to bytes
            val writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES)
            classNode.accept(writer)
            val finalPatchedClassBytes = writer.toByteArray

            PatchResult.PatchedPair(companionObjectName, className, patchedObjectBytes, finalPatchedClassBytes)

          case PatchResult.Failed(err) =>
            PatchResult.Failed(s"Failed to patch companion object: $err")
          case _ =>
            PatchResult.Failed("Unexpected result when patching companion object")
        }
      } else {
        // Simpler case: object has OFFSET in itself, class has its own lazy vals
        // Patch both independently
        val objectPatchResult = patchScala33x_37x(
          companionObjectBytes,
          companionObjectInfo,
          objectLazyVals,
          companionObjectName,
          None,
          None
        )
        val classPatchResult = patchScala33x_37x(classBytes, classInfo, classLazyVals, className, None, None)

        (objectPatchResult, classPatchResult) match {
          case (PatchResult.PatchedSingle(_, objBytes), PatchResult.PatchedSingle(_, clsBytes)) =>
            PatchResult.PatchedPair(companionObjectName, className, objBytes, clsBytes)
          case (PatchResult.Failed(err), _) =>
            PatchResult.Failed(s"Failed to patch companion object: $err")
          case (_, PatchResult.Failed(err)) =>
            PatchResult.Failed(s"Failed to patch companion class: $err")
          case _ =>
            PatchResult.Failed("Unexpected patching result for companion pair")
        }
      }
    } catch {
      case e: Exception =>
        e.printStackTrace()
        PatchResult.Failed(s"Patching failed for companion pair: ${e.getMessage}")
    }
  }

  /** Patches Scala 3.3-3.7 lazy vals to 3.8+ format.
    *
    * Transforms Unsafe-based lazy vals to VarHandle-based implementation. When companion class data is provided,
    * patches both the companion object and class to move OFFSET fields to VarHandle.
    *
    * @param companionInfo
    *   Optional tuple of (className, classInfo, classBytes) for companion class that contains OFFSET fields
    */
  private def patchScala33x_37x(
      bytes: Array[Byte],
      classInfo: lazyvalgrade.classfile.ClassInfo,
      lazyVals: Seq[LazyValInfo],
      name: String,
      companionInfo: Option[(String, lazyvalgrade.classfile.ClassInfo, Array[Byte])],
      unused: Option[Any] = None // For compatibility with old signature
  ): PatchResult = {
    try {
      companionInfo match {
        case Some((companionClassName, companionClassInfo, companionClassBytes)) =>
          // This is a companion object with OFFSET fields in the companion class
          // We need to:
          // 1. Build offsetToVarHandle map from companion class OFFSET fields
          // 2. Patch companion class: remove OFFSET fields (no VarHandle needed)
          // 3. Patch companion object: add VarHandle fields, transform lzyINIT methods, patch <clinit>

          val companionReader = new ClassReader(companionClassBytes)
          val companionClassNode = new ClassNode(ASM9)
          companionReader.accept(companionClassNode, ClassReader.EXPAND_FRAMES)

          // Build offsetToVarHandle map from companion class OFFSET fields
          val offsetToVarHandle = buildOffsetToVarHandleMap(companionClassNode, lazyVals)

          // Patch companion class: just remove OFFSET fields and <clinit>
          patchCompanionClass33x_37x(companionClassNode, lazyVals)

          val companionWriter = new ClassWriter(ClassWriter.COMPUTE_FRAMES)
          companionClassNode.accept(companionWriter)
          val patchedClassBytes = companionWriter.toByteArray

          // Patch companion object: add VarHandle fields, patch <clinit>, transform lzyINIT methods
          val objectReader = new ClassReader(bytes)
          val objectNode = new ClassNode(ASM9)
          objectReader.accept(objectNode, ClassReader.EXPAND_FRAMES)

          // Add VarHandle fields to the object
          offsetToVarHandle.values.toSet.foreach { varHandleName =>
            val fieldNode = new FieldNode(
              ASM9,
              ACC_PRIVATE | ACC_STATIC | ACC_FINAL,
              varHandleName,
              "Ljava/lang/invoke/VarHandle;",
              null,
              null
            )
            objectNode.fields.add(fieldNode)
          }

          // Patch <clinit> in object to initialize VarHandle fields
          objectNode.methods.asScala.find(_.name == "<clinit>").foreach { clinit =>
            patchClinitForCompanionObject(clinit, classInfo.name, lazyVals, offsetToVarHandle)
          }

          // Patch lzyINIT methods in object to use VarHandle
          objectNode.methods.asScala.filter(_.name.matches(".*\\$lzyINIT\\d+")).foreach { lzyInit =>
            patchLzyInitMethod33x_37x(lzyInit, classInfo.name, offsetToVarHandle)
          }

          // Add MethodHandles$Lookup inner class reference to object
          val hasLookupInner = objectNode.innerClasses.asScala.exists { inner =>
            inner.name == "java/lang/invoke/MethodHandles$Lookup"
          }
          if (!hasLookupInner) {
            objectNode.innerClasses.add(
              new InnerClassNode(
                "java/lang/invoke/MethodHandles$Lookup",
                "java/lang/invoke/MethodHandles",
                "Lookup",
                ACC_PUBLIC | ACC_FINAL | ACC_STATIC
              )
            )
          }

          val objectWriter = new ClassWriter(ClassWriter.COMPUTE_FRAMES)
          objectNode.accept(objectWriter)
          val patchedObjectBytes = objectWriter.toByteArray

          PatchResult.PatchedPair(name, companionClassName, patchedObjectBytes, patchedClassBytes)

        case None =>
          // Standalone class/object with OFFSET fields in the same class
          val reader = new ClassReader(bytes)
          val classNode = new ClassNode(ASM9)
          reader.accept(classNode, ClassReader.EXPAND_FRAMES)

          // Perform patching on the class node
          patchClassNode33x_37x(classNode, classInfo.name, lazyVals)

          // Write back to bytes
          val writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES)
          classNode.accept(writer)
          val patchedBytes = writer.toByteArray

          PatchResult.PatchedSingle(name, patchedBytes)
      }
    } catch {
      case e: Exception =>
        e.printStackTrace()
        PatchResult.Failed(s"Patching failed: ${e.getMessage}")
    }
  }

  /** Patches companion class to remove OFFSET fields belonging to the companion object.
    *
    * IMPORTANT: Only removes OFFSET$_m_N fields (companion object's lazy vals).
    * Preserves OFFSET$N fields (class's own lazy vals) for subsequent patching.
    * Does NOT remove <clinit> since it may contain initialization for the class's own OFFSET fields.
    */
  private def patchCompanionClass33x_37x(classNode: ClassNode, lazyVals: Seq[LazyValInfo]): Unit = {
    // Only remove OFFSET$_m_N fields (companion object's lazy vals)
    // Preserve OFFSET$N fields (class's own lazy vals)
    val fieldsToRemove = scala.collection.mutable.Set[FieldNode]()
    classNode.fields.asScala.foreach { field =>
      if (field.desc == "J" && field.name.matches("OFFSET\\$_m_\\d+")) {
        fieldsToRemove.add(field)
      }
    }
    fieldsToRemove.foreach(classNode.fields.remove)

    // Remove initialization of OFFSET$_m_N fields from <clinit>
    // Keep the rest of <clinit> intact for class's own OFFSET fields
    classNode.methods.asScala.find(_.name == "<clinit>").foreach { clinit =>
      val instructions = clinit.instructions
      val toRemove = scala.collection.mutable.ListBuffer[AbstractInsnNode]()

      var current = instructions.getFirst
      while (current != null) {
        current match {
          case fieldInsn: FieldInsnNode
            if fieldInsn.getOpcode == PUTSTATIC &&
               fieldInsn.desc == "J" &&
               fieldInsn.name.matches("OFFSET\\$_m_\\d+") =>
            // Found PUTSTATIC for OFFSET$_m_N field
            // Remove the entire initialization sequence: ALOAD/LDC -> INVOKEVIRTUAL -> PUTSTATIC
            var prev = current.getPrevious
            var removeCount = 0
            // Work backwards to find the start of this field initialization
            while (prev != null && removeCount < 10) { // Safety limit
              prev match {
                case _: FieldInsnNode if prev.getOpcode == GETSTATIC =>
                  // Found start of initialization sequence
                  var temp = prev
                  while (temp != null && temp != current.getNext) {
                    toRemove += temp
                    temp = temp.getNext
                  }
                  removeCount = 999 // Break outer loop
                  prev = null
                case _ =>
                  removeCount += 1
                  prev = prev.getPrevious
              }
            }
          case _ =>
        }
        current = current.getNext
      }

      toRemove.foreach(instructions.remove)
    }
  }

  /** Core patching logic for Scala 3.3-3.7 lazy vals. */
  private def patchClassNode33x_37x(classNode: ClassNode, className: String, lazyVals: Seq[LazyValInfo]): Unit = {
    // Map of OFFSET field names to VarHandle field names
    val offsetToVarHandle = scala.collection.mutable.Map[String, String]()

    // Step 1: Remove OFFSET fields and collect names for VarHandle fields
    val fieldsToRemove = scala.collection.mutable.Set[FieldNode]()
    classNode.fields.asScala.foreach { field =>
      // Match both OFFSET patterns:
      // - OFFSET$_m_N (objects/companions)
      // - OFFSET$N (standalone classes)
      if (field.desc == "J" && (field.name.matches("OFFSET\\$_m_\\d+") || field.name.matches("OFFSET\\$\\d+"))) {
        // Extract index and find corresponding lazy val
        val companionPattern = "OFFSET\\$_m_(\\d+)".r
        val standalonePattern = "OFFSET\\$(\\d+)".r

        field.name match {
          case companionPattern(idx) =>
            val offsetIndex = idx.toInt
            // IMPORTANT: OFFSET$_m_N maps to the Nth lazy val (0-indexed), NOT by lazy val index
            // All lazy vals in a class have the same index (typically 1), so we map by position
            if (offsetIndex < lazyVals.size) {
              val lv = lazyVals(offsetIndex)
              val varHandleName = s"${lv.name}$$lzy${lv.index}$$lzyHandle"
              offsetToVarHandle(field.name) = varHandleName
            }
          case standalonePattern(idx) =>
            val offsetIndex = idx.toInt
            // IMPORTANT: OFFSET$N maps to the Nth lazy val (0-indexed), NOT by lazy val index
            if (offsetIndex < lazyVals.size) {
              val lv = lazyVals(offsetIndex)
              val varHandleName = s"${lv.name}$$lzy${lv.index}$$lzyHandle"
              offsetToVarHandle(field.name) = varHandleName
            }
          case _ =>
        }
        fieldsToRemove.add(field)
      }
    }
    fieldsToRemove.foreach(classNode.fields.remove)

    // Step 2: Add VarHandle fields
    // IMPORTANT: Even if no OFFSET fields were found (e.g., they were already removed by companion patching),
    // we still need to add VarHandle fields for all lazy vals in the lazyVals list
    val varHandlesToAdd = lazyVals.map { lv =>
      s"${lv.name}$$lzy${lv.index}$$lzyHandle"
    }.toSet

    // Check which VarHandle fields already exist
    val existingVarHandles = classNode.fields.asScala
      .filter(f => f.desc == "Ljava/lang/invoke/VarHandle;")
      .map(_.name)
      .toSet

    // Only add VarHandles that don't already exist
    (varHandlesToAdd -- existingVarHandles).foreach { varHandleName =>
      val fieldNode = new FieldNode(
        ASM9,
        ACC_PRIVATE | ACC_STATIC | ACC_FINAL,
        varHandleName,
        "Ljava/lang/invoke/VarHandle;",
        null,
        null
      )
      classNode.fields.add(fieldNode)
    }

    // Step 3: Patch <clinit> method
    // Build complete mapping: for VarHandles without OFFSET fields, we need to add initialization
    val allVarHandles = lazyVals.map { lv =>
      val varHandleName = s"${lv.name}$$lzy${lv.index}$$lzyHandle"
      val storageFieldName = s"${lv.name}$$lzy${lv.index}"
      (varHandleName, storageFieldName)
    }.toMap

    // Step 3b: Patch or create <clinit> if needed
    val clinitOpt = classNode.methods.asScala.find(_.name == "<clinit>")
    clinitOpt match {
      case Some(clinit) =>
        // Patch existing <clinit>
        patchClinitMethod33x_37x(clinit, className, offsetToVarHandle.toMap, allVarHandles)

      case None if allVarHandles.nonEmpty =>
        // Create new <clinit> to initialize VarHandle fields
        val clinit = new MethodNode(
          ASM9,
          ACC_STATIC,
          "<clinit>",
          "()V",
          null,
          null
        )

        // Add VarHandle initialization for each lazy val
        allVarHandles.foreach { case (varHandleName, storageFieldName) =>
          // MethodHandles.lookup()
          clinit.instructions.add(
            new MethodInsnNode(
              INVOKESTATIC,
              "java/lang/invoke/MethodHandles",
              "lookup",
              "()Ljava/lang/invoke/MethodHandles$Lookup;",
              false
            )
          )

          // LDC <Class>
          clinit.instructions.add(new LdcInsnNode(org.objectweb.asm.Type.getObjectType(className.replace('.', '/'))))

          // LDC <FieldName>
          clinit.instructions.add(new LdcInsnNode(storageFieldName))

          // LDC Object.class
          clinit.instructions.add(new LdcInsnNode(org.objectweb.asm.Type.getType("Ljava/lang/Object;")))

          // INVOKEVIRTUAL Lookup.findVarHandle
          clinit.instructions.add(
            new MethodInsnNode(
              INVOKEVIRTUAL,
              "java/lang/invoke/MethodHandles$Lookup",
              "findVarHandle",
              "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/invoke/VarHandle;",
              false
            )
          )

          // PUTSTATIC <Class>.<varHandleName>
          clinit.instructions.add(
            new FieldInsnNode(
              PUTSTATIC,
              className.replace('.', '/'),
              varHandleName,
              "Ljava/lang/invoke/VarHandle;"
            )
          )
        }

        // RETURN
        clinit.instructions.add(new InsnNode(RETURN))

        // Add method to class
        classNode.methods.add(clinit)

      case None =>
        // No clinit and no VarHandles to initialize, do nothing
    }

    // Step 4: Patch lzyINIT methods
    classNode.methods.asScala.filter(_.name.matches(".*\\$lzyINIT\\d+")).foreach { lzyInit =>
      patchLzyInitMethod33x_37x(lzyInit, className, offsetToVarHandle.toMap)
    }

    // Step 5: Add MethodHandles$Lookup inner class reference
    val hasLookupInner = classNode.innerClasses.asScala.exists { inner =>
      inner.name == "java/lang/invoke/MethodHandles$Lookup"
    }
    if (!hasLookupInner) {
      classNode.innerClasses.add(
        new InnerClassNode(
          "java/lang/invoke/MethodHandles$Lookup",
          "java/lang/invoke/MethodHandles",
          "Lookup",
          ACC_PUBLIC | ACC_FINAL | ACC_STATIC
        )
      )
    }
  }

  /** Patches <clinit> for 3.3-3.7: Unsafe offset initialization → VarHandle lookup */
  private def patchClinitMethod33x_37x(
      method: MethodNode,
      className: String,
      offsetToVarHandle: Map[String, String],
      allVarHandles: Map[String, String] // varHandleName -> storageFieldName
  ): Unit = {
    val instructions = method.instructions
    val toRemove = scala.collection.mutable.ArrayBuffer[AbstractInsnNode]()
    val toInsert = scala.collection.mutable.ArrayBuffer[(AbstractInsnNode, java.util.List[AbstractInsnNode])]()

    // Find pattern:
    // GETSTATIC LazyVals$.MODULE$
    // LDC <Class>
    // LDC <FieldName>
    // INVOKEVIRTUAL Class.getDeclaredField
    // INVOKEVIRTUAL LazyVals$.getOffsetStatic
    // PUTSTATIC <Class>.OFFSET$_m_N

    var current = instructions.getFirst
    while (current != null) {
      current match {
        case getStatic: FieldInsnNode
            if getStatic.getOpcode == GETSTATIC &&
              getStatic.owner == "scala/runtime/LazyVals$" &&
              getStatic.name == "MODULE$" =>
          // Found start of pattern
          var next1 = getStatic.getNext
          var next2 = if (next1 != null) next1.getNext else null
          var next3 = if (next2 != null) next2.getNext else null
          var next4 = if (next3 != null) next3.getNext else null
          var next5 = if (next4 != null) next4.getNext else null

          (next1, next2, next3, next4, next5) match {
            case (
                  ldcClass: LdcInsnNode,
                  ldcField: LdcInsnNode,
                  getDeclaredField: MethodInsnNode,
                  getOffsetStatic: MethodInsnNode,
                  putStatic: FieldInsnNode
                )
                if ldcClass.cst.isInstanceOf[org.objectweb.asm.Type] &&
                  ldcField.cst.isInstanceOf[String] &&
                  getDeclaredField.getOpcode == INVOKEVIRTUAL &&
                  getDeclaredField.owner == "java/lang/Class" &&
                  getDeclaredField.name == "getDeclaredField" &&
                  getOffsetStatic.getOpcode == INVOKEVIRTUAL &&
                  getOffsetStatic.owner == "scala/runtime/LazyVals$" &&
                  getOffsetStatic.name == "getOffsetStatic" &&
                  putStatic.getOpcode == PUTSTATIC =>
              // Pattern matched!
              val classType = ldcClass.cst.asInstanceOf[org.objectweb.asm.Type]
              val fieldName = ldcField.cst.asInstanceOf[String]
              val offsetFieldName = putStatic.name

              offsetToVarHandle.get(offsetFieldName).foreach { varHandleName =>
                // Mark for removal
                toRemove.addAll(Seq(getStatic, next1, next2, next3, next4, next5))

                // Create replacement instructions
                val replacement = new java.util.ArrayList[AbstractInsnNode]()

                // INVOKESTATIC MethodHandles.lookup
                replacement.add(
                  new MethodInsnNode(
                    INVOKESTATIC,
                    "java/lang/invoke/MethodHandles",
                    "lookup",
                    "()Ljava/lang/invoke/MethodHandles$Lookup;",
                    false
                  )
                )

                // LDC <Class>
                replacement.add(new LdcInsnNode(classType))

                // LDC <FieldName>
                replacement.add(new LdcInsnNode(fieldName))

                // LDC Object.class
                replacement.add(new LdcInsnNode(org.objectweb.asm.Type.getType("Ljava/lang/Object;")))

                // INVOKEVIRTUAL Lookup.findVarHandle
                replacement.add(
                  new MethodInsnNode(
                    INVOKEVIRTUAL,
                    "java/lang/invoke/MethodHandles$Lookup",
                    "findVarHandle",
                    "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/invoke/VarHandle;",
                    false
                  )
                )

                // PUTSTATIC <Class>.<varHandleName>
                replacement.add(
                  new FieldInsnNode(
                    PUTSTATIC,
                    classType.getInternalName,
                    varHandleName,
                    "Ljava/lang/invoke/VarHandle;"
                  )
                )

                toInsert.append((getStatic, replacement))
              }

            case _ =>
            // Pattern didn't match completely, continue
          }

        case _ =>
        // Not the start of our pattern
      }
      current = current.getNext
    }

    // Apply transformations
    toInsert.foreach { case (anchor, newInsns) =>
      newInsns.asScala.foreach(instructions.insertBefore(anchor, _))
    }
    toRemove.foreach(instructions.remove)

    // Add initialization for VarHandles that don't have OFFSET fields (e.g., after companion patching)
    val initializedVarHandles = offsetToVarHandle.values.toSet
    val uninitializedVarHandles = allVarHandles.filterKeys(!initializedVarHandles.contains(_))

    if (uninitializedVarHandles.nonEmpty) {
      // Find a good insertion point (before RETURN)
      var returnInsn: AbstractInsnNode = null
      var current = instructions.getLast
      while (current != null && returnInsn == null) {
        if (current.getOpcode == RETURN) {
          returnInsn = current
        }
        current = current.getPrevious
      }

      if (returnInsn != null) {
        uninitializedVarHandles.foreach { case (varHandleName, storageFieldName) =>
          val newInsns = new java.util.ArrayList[AbstractInsnNode]()

          // INVOKESTATIC MethodHandles.lookup
          newInsns.add(
            new MethodInsnNode(
              INVOKESTATIC,
              "java/lang/invoke/MethodHandles",
              "lookup",
              "()Ljava/lang/invoke/MethodHandles$Lookup;",
              false
            )
          )

          // LDC <Class>
          newInsns.add(new LdcInsnNode(org.objectweb.asm.Type.getObjectType(className.replace('.', '/'))))

          // LDC <FieldName>
          newInsns.add(new LdcInsnNode(storageFieldName))

          // LDC Object.class
          newInsns.add(new LdcInsnNode(org.objectweb.asm.Type.getType("Ljava/lang/Object;")))

          // INVOKEVIRTUAL Lookup.findVarHandle
          newInsns.add(
            new MethodInsnNode(
              INVOKEVIRTUAL,
              "java/lang/invoke/MethodHandles$Lookup",
              "findVarHandle",
              "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/invoke/VarHandle;",
              false
            )
          )

          // PUTSTATIC <Class>.<varHandleName>
          newInsns.add(
            new FieldInsnNode(
              PUTSTATIC,
              className.replace('.', '/'),
              varHandleName,
              "Ljava/lang/invoke/VarHandle;"
            )
          )

          newInsns.asScala.foreach(instructions.insertBefore(returnInsn, _))
        }
      }
    }
  }

  /** Patches lzyINIT for 3.3-3.7: objCAS → VarHandle.compareAndSet */
  private def patchLzyInitMethod33x_37x(
      method: MethodNode,
      className: String,
      offsetToVarHandle: Map[String, String]
  ): Unit = {
    val instructions = method.instructions
    val toRemove = scala.collection.mutable.ArrayBuffer[AbstractInsnNode]()
    val toInsert = scala.collection.mutable.ArrayBuffer[(AbstractInsnNode, java.util.List[AbstractInsnNode])]()

    // Find pattern:
    // GETSTATIC LazyVals$.MODULE$
    // ALOAD 0
    // GETSTATIC <Class>.OFFSET$_m_N
    // [expected value]
    // [new value]
    // INVOKEVIRTUAL LazyVals$.objCAS

    var current = instructions.getFirst
    while (current != null) {
      current match {
        case getStatic: FieldInsnNode
            if getStatic.getOpcode == GETSTATIC &&
              getStatic.owner == "scala/runtime/LazyVals$" &&
              getStatic.name == "MODULE$" =>
          // Found potential start of CAS pattern
          var next1 = getStatic.getNext
          var next2 = if (next1 != null) next1.getNext else null

          (next1, next2) match {
            case (aload: VarInsnNode, getOffset: FieldInsnNode)
                if aload.getOpcode == ALOAD &&
                  aload.`var` == 0 &&
                  getOffset.getOpcode == GETSTATIC &&
                  (getOffset.name.matches("OFFSET\\$_m_\\d+") || getOffset.name.matches("OFFSET\\$\\d+")) =>
              // Pattern start matched, find objCAS call
              var scanPtr = next2.getNext
              var expectedInsns = scala.collection.mutable.ArrayBuffer[AbstractInsnNode]()
              var foundObjCas = false
              var objCasNode: MethodInsnNode = null

              // Collect instructions until we hit objCAS (max 10 instructions)
              var count = 0
              while (scanPtr != null && count < 20 && !foundObjCas) {
                scanPtr match {
                  case methodInsn: MethodInsnNode
                      if methodInsn.getOpcode == INVOKEVIRTUAL &&
                        methodInsn.owner == "scala/runtime/LazyVals$" &&
                        methodInsn.name == "objCAS" =>
                    foundObjCas = true
                    objCasNode = methodInsn

                  case _ =>
                    expectedInsns.append(scanPtr)
                }
                scanPtr = scanPtr.getNext
                count += 1
              }

              if (foundObjCas && objCasNode != null) {
                offsetToVarHandle.get(getOffset.name).foreach { varHandleName =>
                  // Mark for removal: GETSTATIC LazyVals$, ALOAD 0, GETSTATIC OFFSET, objCAS
                  toRemove.addAll(Seq(getStatic, next1, next2, objCasNode))

                  // Create replacement
                  val replacement = new java.util.ArrayList[AbstractInsnNode]()

                  // GETSTATIC <Class>.<varHandleName>
                  replacement.add(
                    new FieldInsnNode(
                      GETSTATIC,
                      className.replace('.', '/'),
                      varHandleName,
                      "Ljava/lang/invoke/VarHandle;"
                    )
                  )

                  // ALOAD 0
                  replacement.add(new VarInsnNode(ALOAD, 0))

                  // [expected and new values are already on the stack, keep them]

                  // Insert replacement before the pattern
                  toInsert.append((getStatic, replacement))

                  // Add VarHandle.compareAndSet after the arguments
                  val casCall = new java.util.ArrayList[AbstractInsnNode]()
                  casCall.add(
                    new MethodInsnNode(
                      INVOKEVIRTUAL,
                      "java/lang/invoke/VarHandle",
                      "compareAndSet",
                      "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Z",
                      false
                    )
                  )
                  toInsert.append((objCasNode, casCall))
                }
              }

            case _ =>
            // Pattern didn't match
          }

        case _ =>
        // Not the start of our pattern
      }
      current = current.getNext
    }

    // Apply transformations
    toInsert.foreach { case (anchor, newInsns) =>
      newInsns.asScala.foreach(instructions.insertBefore(anchor, _))
    }
    toRemove.foreach(instructions.remove)
  }

  /** Builds offsetToVarHandle map by scanning companion class for OFFSET fields. */
  private def buildOffsetToVarHandleMap(
      companionClassNode: ClassNode,
      lazyVals: Seq[LazyValInfo]
  ): Map[String, String] = {
    val offsetToVarHandle = scala.collection.mutable.Map[String, String]()

    companionClassNode.fields.asScala.foreach { field =>
      if (field.desc == "J" && (field.name.matches("OFFSET\\$_m_\\d+") || field.name.matches("OFFSET\\$\\d+"))) {
        val companionPattern = "OFFSET\\$_m_(\\d+)".r
        val standalonePattern = "OFFSET\\$(\\d+)".r

        field.name match {
          case companionPattern(idx) =>
            val offsetIndex = idx.toInt
            // IMPORTANT: OFFSET$_m_N maps to the Nth lazy val (0-indexed), NOT by lazy val index
            if (offsetIndex < lazyVals.size) {
              val lv = lazyVals(offsetIndex)
              val varHandleName = s"${lv.name}$$lzy${lv.index}$$lzyHandle"
              offsetToVarHandle(field.name) = varHandleName
            }
          case standalonePattern(idx) =>
            val offsetIndex = idx.toInt
            // IMPORTANT: OFFSET$N maps to the Nth lazy val (0-indexed), NOT by lazy val index
            if (offsetIndex < lazyVals.size) {
              val lv = lazyVals(offsetIndex)
              val varHandleName = s"${lv.name}$$lzy${lv.index}$$lzyHandle"
              offsetToVarHandle(field.name) = varHandleName
            }
          case _ =>
        }
      }
    }

    offsetToVarHandle.toMap
  }

  /** Patches companion class: removes OFFSET fields, adds VarHandle fields, patches/creates <clinit>. */
  private def patchCompanionClassWithVarHandles(
      companionClassNode: ClassNode,
      companionClassName: String,
      lazyVals: Seq[LazyValInfo],
      offsetToVarHandle: Map[String, String]
  ): Unit = {
    // Step 1: Remove OFFSET fields
    val fieldsToRemove = scala.collection.mutable.Set[FieldNode]()
    companionClassNode.fields.asScala.foreach { field =>
      if (field.desc == "J" && (field.name.matches("OFFSET\\$_m_\\d+") || field.name.matches("OFFSET\\$\\d+"))) {
        fieldsToRemove.add(field)
      }
    }
    fieldsToRemove.foreach(companionClassNode.fields.remove)

    // Step 2: Add VarHandle fields
    offsetToVarHandle.values.toSet.foreach { varHandleName =>
      val fieldNode = new FieldNode(
        ASM9,
        ACC_PRIVATE | ACC_STATIC | ACC_FINAL,
        varHandleName,
        "Ljava/lang/invoke/VarHandle;",
        null,
        null
      )
      companionClassNode.fields.add(fieldNode)
    }

    // Step 3: Patch or create <clinit> method
    // Build complete mapping for all VarHandles
    val allVarHandles = lazyVals.map { lv =>
      val varHandleName = s"${lv.name}$$lzy${lv.index}$$lzyHandle"
      val storageFieldName = s"${lv.name}$$lzy${lv.index}"
      (varHandleName, storageFieldName)
    }.toMap

    val clinitOpt = companionClassNode.methods.asScala.find(_.name == "<clinit>")
    clinitOpt match {
      case Some(clinit) =>
        // Patch existing <clinit>: replace OFFSET initialization with VarHandle initialization
        patchClinitMethod33x_37x(clinit, companionClassName, offsetToVarHandle, allVarHandles)
      case None =>
        // Create new <clinit> to initialize VarHandle fields
        val clinit = new MethodNode(
          ASM9,
          ACC_STATIC,
          "<clinit>",
          "()V",
          null,
          null
        )
        val instructions = clinit.instructions

        // Generate VarHandle initialization for each lazy val
        offsetToVarHandle.foreach { case (offsetField, varHandleName) =>
          // Find the corresponding lazy val
          lazyVals.find(lv => varHandleName == s"${lv.name}$$lzy${lv.index}$$lzyHandle").foreach { lv =>
            // MethodHandles.lookup()
            instructions.add(
              new MethodInsnNode(
                INVOKESTATIC,
                "java/lang/invoke/MethodHandles",
                "lookup",
                "()Ljava/lang/invoke/MethodHandles$Lookup;",
                false
              )
            )

            // LDC class
            instructions.add(new LdcInsnNode(Type.getObjectType(companionClassName.replace('.', '/'))))

            // LDC field name
            instructions.add(new LdcInsnNode(lv.storageField.name))

            // LDC field type
            instructions.add(new LdcInsnNode(Type.getType(lv.storageField.descriptor)))

            // findVarHandle
            instructions.add(
              new MethodInsnNode(
                INVOKEVIRTUAL,
                "java/lang/invoke/MethodHandles$Lookup",
                "findVarHandle",
                "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/invoke/VarHandle;",
                false
              )
            )

            // PUTSTATIC
            instructions.add(
              new FieldInsnNode(
                PUTSTATIC,
                companionClassName.replace('.', '/'),
                varHandleName,
                "Ljava/lang/invoke/VarHandle;"
              )
            )
          }
        }

        // RETURN
        instructions.add(new InsnNode(RETURN))

        companionClassNode.methods.add(clinit)
    }

    // Step 4: Add MethodHandles$Lookup inner class reference
    val hasLookupInner = companionClassNode.innerClasses.asScala.exists { inner =>
      inner.name == "java/lang/invoke/MethodHandles$Lookup"
    }
    if (!hasLookupInner) {
      companionClassNode.innerClasses.add(
        new InnerClassNode(
          "java/lang/invoke/MethodHandles$Lookup",
          "java/lang/invoke/MethodHandles",
          "Lookup",
          ACC_PUBLIC | ACC_FINAL | ACC_STATIC
        )
      )
    }
  }

  /** Patches <clinit> in companion object to initialize VarHandle fields at the beginning. */
  private def patchClinitForCompanionObject(
      method: MethodNode,
      objectClassName: String,
      lazyVals: Seq[LazyValInfo],
      offsetToVarHandle: Map[String, String]
  ): Unit = {
    val instructions = method.instructions
    val firstInsn = instructions.getFirst

    // Generate VarHandle initialization for each lazy val and insert at the beginning
    offsetToVarHandle.foreach { case (offsetField, varHandleName) =>
      lazyVals.find(lv => varHandleName == s"${lv.name}$$lzy${lv.index}$$lzyHandle").foreach { lv =>
        val newInsns = new java.util.ArrayList[AbstractInsnNode]()

        // MethodHandles.lookup()
        newInsns.add(
          new MethodInsnNode(
            INVOKESTATIC,
            "java/lang/invoke/MethodHandles",
            "lookup",
            "()Ljava/lang/invoke/MethodHandles$Lookup;",
            false
          )
        )

        // LDC class (the object class where storage field is)
        newInsns.add(new LdcInsnNode(Type.getObjectType(objectClassName.replace('.', '/'))))

        // LDC field name
        newInsns.add(new LdcInsnNode(lv.storageField.name))

        // LDC field type
        newInsns.add(new LdcInsnNode(Type.getType(lv.storageField.descriptor)))

        // findVarHandle
        newInsns.add(
          new MethodInsnNode(
            INVOKEVIRTUAL,
            "java/lang/invoke/MethodHandles$Lookup",
            "findVarHandle",
            "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/invoke/VarHandle;",
            false
          )
        )

        // PUTSTATIC (store in object class)
        newInsns.add(
          new FieldInsnNode(
            PUTSTATIC,
            objectClassName.replace('.', '/'),
            varHandleName,
            "Ljava/lang/invoke/VarHandle;"
          )
        )

        // Insert all instructions before the first instruction
        newInsns.asScala.foreach(instructions.insertBefore(firstInsn, _))
      }
    }
  }

  /** Patches lzyINIT method to use VarHandle from companion class instead of OFFSET. */
  private def patchLzyInitMethodWithCompanionVarHandle(
      method: MethodNode,
      companionClassName: String,
      offsetToVarHandle: Map[String, String]
  ): Unit = {
    val instructions = method.instructions
    val toRemove = scala.collection.mutable.Set[AbstractInsnNode]()
    val toInsert = scala.collection.mutable.ArrayBuffer[(AbstractInsnNode, java.util.List[AbstractInsnNode])]()

    // Find pattern:
    // GETSTATIC LazyVals$.MODULE$
    // ALOAD 0
    // GETSTATIC <CompanionClass>.OFFSET$_m_N
    // [expected value]
    // [new value]
    // INVOKEVIRTUAL LazyVals$.objCAS

    var current = instructions.getFirst
    while (current != null) {
      current match {
        case getStatic: FieldInsnNode
            if getStatic.getOpcode == GETSTATIC &&
              getStatic.owner == "scala/runtime/LazyVals$" &&
              getStatic.name == "MODULE$" =>
          var next1 = getStatic.getNext
          var next2 = if (next1 != null) next1.getNext else null

          (next1, next2) match {
            case (aload: VarInsnNode, getOffset: FieldInsnNode)
                if aload.getOpcode == ALOAD &&
                  aload.`var` == 0 &&
                  getOffset.getOpcode == GETSTATIC &&
                  (getOffset.name.matches("OFFSET\\$_m_\\d+") || getOffset.name.matches("OFFSET\\$\\d+")) =>
              // Pattern start matched, find objCAS call
              var scanPtr = next2.getNext
              var foundObjCas = false
              var objCasNode: MethodInsnNode = null

              var count = 0
              while (scanPtr != null && count < 20 && !foundObjCas) {
                scanPtr match {
                  case methodInsn: MethodInsnNode
                      if methodInsn.getOpcode == INVOKEVIRTUAL &&
                        methodInsn.owner == "scala/runtime/LazyVals$" &&
                        methodInsn.name == "objCAS" =>
                    foundObjCas = true
                    objCasNode = methodInsn

                  case _ =>
                }
                scanPtr = scanPtr.getNext
                count += 1
              }

              if (foundObjCas && objCasNode != null) {
                offsetToVarHandle.get(getOffset.name).foreach { varHandleName =>
                  // Mark for removal: GETSTATIC LazyVals$, ALOAD 0, GETSTATIC OFFSET, objCAS
                  toRemove.addAll(Seq(getStatic, next1, next2, objCasNode))

                  // Create replacement
                  val replacement = new java.util.ArrayList[AbstractInsnNode]()

                  // GETSTATIC <CompanionClass>.<varHandleName>
                  replacement.add(
                    new FieldInsnNode(
                      GETSTATIC,
                      companionClassName.replace('.', '/'),
                      varHandleName,
                      "Ljava/lang/invoke/VarHandle;"
                    )
                  )

                  // ALOAD 0
                  replacement.add(new VarInsnNode(ALOAD, 0))

                  // Insert replacement before the pattern
                  toInsert.append((getStatic, replacement))

                  // Add VarHandle.compareAndSet after the arguments
                  val casCall = new java.util.ArrayList[AbstractInsnNode]()
                  casCall.add(
                    new MethodInsnNode(
                      INVOKEVIRTUAL,
                      "java/lang/invoke/VarHandle",
                      "compareAndSet",
                      "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Z",
                      false
                    )
                  )
                  toInsert.append((objCasNode, casCall))
                }
              }

            case _ =>
          }

        case _ =>
      }
      current = current.getNext
    }

    // Apply transformations
    toInsert.foreach { case (anchor, newInsns) =>
      newInsns.asScala.foreach(instructions.insertBefore(anchor, _))
    }
    toRemove.foreach(instructions.remove)
  }
}
