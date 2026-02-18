package lazyvalgrade.agent

import java.lang.instrument.ClassFileTransformer
import java.security.ProtectionDomain
import java.util.concurrent.ConcurrentHashMap
import org.objectweb.asm.{ClassReader, ClassVisitor, FieldVisitor, Opcodes}
import lazyvalgrade.analysis.LazyValAnalyzer
import lazyvalgrade.patching.BytecodePatcher

/** ClassFileTransformer that patches Scala 3.0-3.7 lazy val bytecode at load time.
  *
  * Uses the group-based patching API to correctly handle companion pairs where
  * lazy val implementation is split across object and class files. When one side
  * of a companion pair is loaded, the other side is read via getResourceAsStream
  * (pure I/O, no class loading) and both are patched together. The companion's
  * patched bytes are buffered for when it actually loads.
  */
class LazyValGradeTransformer(config: AgentConfig) extends ClassFileTransformer {

  /** Packages to always skip (JDK internals, our own code, Scala runtime). */
  private val skipPrefixes = Array(
    "java/", "javax/", "jdk/", "sun/", "com/sun/",
    "lazyvalgrade/", "scala/runtime/"
  )

  /** Buffer for patched companion bytes. When one side of a companion pair is patched,
    * the other side's bytes are stored here and consumed (via remove) when that class loads.
    */
  private val patchedCompanionBuffer = new ConcurrentHashMap[String, Array[Byte]]()

  private def debug(msg: => String): Unit =
    if (config.debug) scribe.debug(msg)

  override def transform(
      loader: ClassLoader,
      className: String,
      classBeingRedefined: Class[?],
      protectionDomain: ProtectionDomain,
      classfileBuffer: Array[Byte]
  ): Array[Byte] = {
    // Fast reject: null className or JDK/internal packages
    if (className == null) return null
    if (skipPrefixes.exists(className.startsWith)) return null

    // Apply include/exclude filters
    if (!config.shouldTransform(className)) return null

    val dotName = className.replace('/', '.')

    // Check if companion already patched this class
    val buffered = patchedCompanionBuffer.remove(dotName)
    if (buffered != null) {
      debug(s"transform($dotName): returning buffered companion bytes (${buffered.length} bytes)")
      if (config.verbose) {
        System.err.println(s"[lazyvalgrade] Patched (buffered): $dotName")
      }
      return buffered
    }

    // Quick field scan: only proceed if class has $lzy or OFFSET$ fields
    val relevant = hasRelevantFields(classfileBuffer)
    if (!relevant) {
      debug(s"transform($dotName): no relevant fields, skipping")
      return null
    }

    // Null loader means bootstrap classloader — already filtered by skipPrefixes
    if (loader == null) {
      debug(s"transform($dotName): null loader, skipping")
      return null
    }

    debug(s"transform($dotName): has relevant fields, attempting patch (loader=${loader.getClass.getName})")

    try {
      // Compute companion name: Foo$ <-> Foo
      val companionDotName =
        if (dotName.endsWith("$")) dotName.stripSuffix("$")
        else dotName + "$"

      // Load companion bytes via getResourceAsStream (pure I/O, no class definition)
      val companionInternalName = companionDotName.replace('.', '/')
      val companionBytes: Option[Array[Byte]] =
        try {
          val stream = loader.getResourceAsStream(companionInternalName + ".class")
          if (stream != null) {
            try {
              val bytes = Some(stream.readAllBytes())
              debug(s"  companion $companionDotName: found (${bytes.get.length} bytes)")
              bytes
            } finally stream.close()
          } else {
            debug(s"  companion $companionDotName: not found (getResourceAsStream returned null)")
            None
          }
        } catch {
          case t: Throwable =>
            debug(s"  companion $companionDotName: error reading: ${t.getMessage}")
            None
        }

      // Build classfile map (dot-separated names)
      val classfileMap: Map[String, Array[Byte]] = companionBytes match {
        case Some(bytes) => Map(dotName -> classfileBuffer, companionDotName -> bytes)
        case None        => Map(dotName -> classfileBuffer)
      }

      debug(s"  classfileMap keys: ${classfileMap.keys.mkString(", ")}")

      // Group and patch
      LazyValAnalyzer.group(classfileMap) match {
        case Left(error) =>
          debug(s"  group() failed: $error")
          if (config.verbose) {
            System.err.println(s"[lazyvalgrade] Failed to group $dotName: $error")
          }
          null

        case Right(groups) =>
          debug(s"  group() returned ${groups.size} group(s): ${groups.map(g => s"${g.getClass.getSimpleName}(${g.primaryName})").mkString(", ")}")

          // There should be exactly one group (single or companion pair)
          groups.headOption.map(BytecodePatcher.patch) match {
            case Some(BytecodePatcher.PatchResult.PatchedSingle(name, patchedBytes)) =>
              debug(s"  patch() -> PatchedSingle($name, ${patchedBytes.length} bytes)")
              if (config.debug) dumpBytes(name, patchedBytes)
              if (config.verbose) {
                System.err.println(s"[lazyvalgrade] Patched: $dotName")
              }
              patchedBytes

            case Some(BytecodePatcher.PatchResult.PatchedPair(objName, clsName, objBytes, clsBytes)) =>
              // Determine which side is the current class and buffer the other
              val (currentBytes, companionName, companionPatchedBytes) =
                if (dotName == objName) (objBytes, clsName, clsBytes)
                else (clsBytes, objName, objBytes)

              patchedCompanionBuffer.put(companionName, companionPatchedBytes)

              debug(s"  patch() -> PatchedPair(obj=$objName ${objBytes.length}b, cls=$clsName ${clsBytes.length}b)")
              debug(s"  returning ${currentBytes.length} bytes for $dotName, buffered ${companionPatchedBytes.length} bytes for $companionName")
              if (config.debug) {
                dumpBytes(objName, objBytes)
                dumpBytes(clsName, clsBytes)
              }
              if (config.verbose) {
                System.err.println(s"[lazyvalgrade] Patched pair: $dotName (buffered companion: $companionName)")
              }
              currentBytes

            case Some(BytecodePatcher.PatchResult.NotApplicable) =>
              debug(s"  patch() -> NotApplicable")
              null

            case Some(BytecodePatcher.PatchResult.Failed(error)) =>
              debug(s"  patch() -> Failed: $error")
              if (config.verbose) {
                System.err.println(s"[lazyvalgrade] Failed to patch $dotName: $error")
              }
              null

            case None =>
              debug(s"  no groups to patch")
              null
          }
      }
    } catch {
      case t: Throwable =>
        debug(s"  EXCEPTION in transform($dotName): ${t.getClass.getName}: ${t.getMessage}")
        if (config.debug) {
          val sw = new java.io.StringWriter()
          t.printStackTrace(new java.io.PrintWriter(sw))
          debug(s"  stack trace:\n$sw")
        }
        // Never throw from a ClassFileTransformer — return null to leave class unchanged
        null
    }
  }

  /** Dump patched bytes to /tmp for post-mortem inspection. */
  private def dumpBytes(name: String, bytes: Array[Byte]): Unit = {
    try {
      val pid = ProcessHandle.current().pid()
      val safeName = name.replace('.', '_').replace('$', '_')
      val path = java.nio.file.Paths.get(s"/tmp/lazyvalgrade-dump-$pid-$safeName.class")
      java.nio.file.Files.write(path, bytes)
      debug(s"  dumped ${bytes.length} bytes to $path")
    } catch {
      case t: Throwable => debug(s"  failed to dump bytes: ${t.getMessage}")
    }
  }

  /** Quick check for relevant fields using ASM with SKIP_CODE | SKIP_DEBUG. */
  private def hasRelevantFields(bytes: Array[Byte]): Boolean = {
    var found = false
    val fields = scala.collection.mutable.ArrayBuffer[String]()
    try {
      val reader = new ClassReader(bytes)
      reader.accept(
        new ClassVisitor(Opcodes.ASM9) {
          override def visitField(
              access: Int,
              name: String,
              descriptor: String,
              signature: String,
              value: Any
          ): FieldVisitor = {
            if (config.debug) fields += s"$name:$descriptor"
            if (!found) {
              if (name.contains("$lzy") && !name.contains("$lzyHandle")) {
                found = true
              } else if (descriptor == "J" && (name.startsWith("OFFSET$_m_") || name.startsWith("OFFSET$"))) {
                found = true
              }
            }
            null // Don't visit field annotations
          }
        },
        ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG
      )
    } catch {
      case _: Throwable => // Malformed classfile — skip
    }
    if (config.debug && found) {
      debug(s"  hasRelevantFields: true, fields=[${fields.mkString(", ")}]")
    }
    found
  }
}
