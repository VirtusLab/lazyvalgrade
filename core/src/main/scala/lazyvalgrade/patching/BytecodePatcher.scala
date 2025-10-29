package lazyvalgrade.patching

import org.objectweb.asm.*
import org.objectweb.asm.tree.*
import org.objectweb.asm.Opcodes.*
import scala.jdk.CollectionConverters.*
import lazyvalgrade.classfile.ClassfileParser
import lazyvalgrade.lazyval.{LazyValDetector, LazyValInfo, LazyValDetectionResult, ScalaVersion}

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
    case class Patched(bytes: Array[Byte]) extends PatchResult
    case object NotApplicable extends PatchResult
    final case class Failed(error: String) extends PatchResult
  }

  /** Patches a classfile to 3.8+ format, dispatching to version-specific implementations.
    *
    * @param bytes
    *   The original classfile bytes
    * @return
    *   PatchResult indicating success, failure, or not applicable
    */
  def patch(bytes: Array[Byte]): PatchResult = {
    // Parse and detect lazy vals
    val classInfo = ClassfileParser.parse(bytes) match {
      case Right(info) => info
      case Left(error) => return PatchResult.Failed(s"Failed to parse classfile: $error")
    }

    val detectionResult = LazyValDetector.detect(classInfo)

    // Extract lazy vals and version, return early if not applicable
    val (lazyVals, version) = detectionResult match {
      case LazyValDetectionResult.NoLazyVals => return PatchResult.NotApplicable
      case LazyValDetectionResult.LazyValsFound(lvs, ver) => (lvs, ver)
      case LazyValDetectionResult.MixedVersions(_) => return PatchResult.Failed("Mixed Scala versions detected")
    }

    // Dispatch to version-specific patching strategy
    version match {
      case ScalaVersion.Scala30x_31x => patchScala30x_31x(bytes, classInfo, lazyVals)
      case ScalaVersion.Scala32x => patchScala32x(bytes, classInfo, lazyVals)
      case ScalaVersion.Scala33x_37x => patchScala33x_37x(bytes, classInfo, lazyVals)
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
      lazyVals: Seq[LazyValInfo]
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
      lazyVals: Seq[LazyValInfo]
  ): PatchResult = {
    PatchResult.Failed("Scala 3.2 lazy val patching is not yet implemented")
  }

  // ============================================================================
  // Scala 3.3-3.7 Patching Strategy (Unsafe → VarHandle transformation)
  // ============================================================================

  /** Patches Scala 3.3-3.7 lazy vals to 3.8+ format.
    *
    * Transforms Unsafe-based lazy vals to VarHandle-based implementation.
    */
  private def patchScala33x_37x(
      bytes: Array[Byte],
      classInfo: lazyvalgrade.classfile.ClassInfo,
      lazyVals: Seq[LazyValInfo]
  ): PatchResult = {
    try {
      val reader = new ClassReader(bytes)
      val classNode = new ClassNode(ASM9)
      reader.accept(classNode, ClassReader.EXPAND_FRAMES)

      // Perform patching on the class node
      patchClassNode33x_37x(classNode, classInfo.name, lazyVals)

      // Write back to bytes
      val writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES)
      classNode.accept(writer)

      PatchResult.Patched(writer.toByteArray)
    } catch {
      case e: Exception =>
        e.printStackTrace()
        PatchResult.Failed(s"Patching failed: ${e.getMessage}")
    }
  }

  /** Core patching logic for Scala 3.3-3.7 lazy vals. */
  private def patchClassNode33x_37x(classNode: ClassNode, className: String, lazyVals: Seq[LazyValInfo]): Unit = {
    // Map of OFFSET field names to VarHandle field names
    val offsetToVarHandle = scala.collection.mutable.Map[String, String]()

    // Step 1: Remove OFFSET fields and collect names for VarHandle fields
    val fieldsToRemove = scala.collection.mutable.Set[FieldNode]()
    classNode.fields.asScala.foreach { field =>
      if (field.name.matches("OFFSET\\$_m_\\d+") && field.desc == "J") {
        // Extract index and find corresponding lazy val
        // Note: OFFSET$_m_N has index N, but lazy val has index N+1
        val offsetPattern = "OFFSET\\$_m_(\\d+)".r
        field.name match {
          case offsetPattern(idx) =>
            val offsetIndex = idx.toInt
            val lazyValIndex = offsetIndex + 1 // Lazy val index is offset index + 1
            lazyVals.find(_.index == lazyValIndex).foreach { lv =>
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
    offsetToVarHandle.values.toSet.foreach { varHandleName =>
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
    classNode.methods.asScala.find(_.name == "<clinit>").foreach { clinit =>
      patchClinitMethod33x_37x(clinit, className, offsetToVarHandle.toMap)
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
      offsetToVarHandle: Map[String, String]
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
                  getOffset.name.matches("OFFSET\\$_m_\\d+") =>
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
}
