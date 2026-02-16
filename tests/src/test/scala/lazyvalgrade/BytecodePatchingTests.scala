package lazyvalgrade

import munit.FunSuite
import lazyvalgrade.classfile.ClassfileParser
import lazyvalgrade.lazyval.{LazyValDetector, LazyValDetectionResult, ScalaVersion, SemanticLazyValComparator}
import lazyvalgrade.patching.BytecodePatcher
import lazyvalgrade.analysis.{LazyValAnalyzer, ClassfileGroup}
import java.nio.file.{Files, Paths, Path}
import scala.util.{Try, Using, boundary}
import scala.util.boundary.break
import scala.collection.immutable.TreeSet

/** End-to-end test suite for bytecode patching.
  *
  * Tests the complete patching pipeline:
  *   1. Compile examples with multiple Scala versions (3.0-3.8) 2. Patch 3.3-3.7 bytecode to 3.8 format 3. Semantically
  *      compare patched versions with real 3.8 4. Runtime testing: verify Unsafe warnings and correct output
  *
  * Use SELECT_EXAMPLE environment variable to filter examples:
  *   - SELECT_EXAMPLE=simple-lazy-val (single example)
  *   - SELECT_EXAMPLE=simple-lazy-val,class-lazy-val (multiple examples)
  */
class BytecodePatchingTests extends FunSuite with ExampleLoader {

  // Increase timeout for tests that compile multiple Scala versions
  override val munitTimeout = scala.concurrent.duration.Duration(180, "s")

  // ===== ExampleLoader implementation =====

  override val examplesDir: os.Path = os.pwd / "tests" / "src" / "test" / "resources" / "fixtures" / "examples"
  override val testWorkspace: os.Path = os.temp.dir(prefix = "lazyvalgrade-patching-tests-", deleteOnExit = false)
  override val quietCompilation: Boolean = true

  /** Scala versions for testing */
  val testVersions: Seq[String] = Seq(
    "3.3.0",
    "3.3.6",
    "3.4.3",
    "3.5.2",
    "3.6.4",
    "3.7.3",
    "3.8.0-RC1-bin-20251026-5c51b7b-NIGHTLY"
  )

  override def requiredScalaVersions: Seq[String] = testVersions

  /** Versions that need patching (3.3-3.7) */
  val patchableVersions: Set[String] = Set(
    "3.3.0",
    "3.3.6",
    "3.4.3",
    "3.5.2",
    "3.6.4",
    "3.7.3"
  )

  /** Target version (3.8) */
  val targetVersion: String = "3.8.0-RC1-bin-20251026-5c51b7b-NIGHTLY"

  /** Patched workspace for transformed classfiles */
  val patchedWorkspace: os.Path = testWorkspace / "patched"

  val cleanupWorkspace: Boolean = false

  override val quietTests: Boolean = false // BytecodePatchingTests is quiet by default

  /** Helper to print only when not in quiet mode */
  private def log(msg: => String): Unit = if !quietTests then println(msg)

  override def beforeAll(): Unit = {
    // Configure scribe logging
    scribe.Logger.root
      .clearHandlers()
      .clearModifiers()
      .withHandler(minimumLevel = Some(scribe.Level.Warn))
      .replace()

    // Verify Java version
    val javaVersionOutput = os.proc("java", "--version").call().out.text().trim
    val javaVersionLine = javaVersionOutput.linesIterator.toSeq.headOption.getOrElse("")

    log(s"Java version: $javaVersionLine")

    // Check if it's Java 24+ (required for Unsafe warning message)
    // Parse versions like "openjdk 17.0.9", 'openjdk version "25"', "java 25", "openjdk 25 2025-09-16"
    // Match the first number that appears after "java" or "openjdk"
    val versionPattern = """(?:java|openjdk)(?:\s+version)?\s+(\d+).*""".r
    javaVersionLine match {
      case versionPattern(major) =>
        val majorVersion = major.toInt
        if (majorVersion < 24) {
          throw new RuntimeException(s"Java 24+ required for runtime tests (Unsafe warnings), found Java $majorVersion")
        }
        log(s"✓ Using Java $majorVersion")
      case _ =>
        throw new RuntimeException(s"Could not parse Java version from: $javaVersionLine")
    }
  }

  /** Load and compile all examples */
  lazy val examples: Seq[LoadedExample] = {
    val fixturesDir = os.pwd / "tests" / "src" / "test" / "resources" / "fixtures"

    log(s"Fixtures directory: $fixturesDir")
    log(s"Examples directory: $examplesDir")
    log(s"Test workspace: $testWorkspace")
    log(s"Patched workspace: $patchedWorkspace")

    os.makeDir.all(patchedWorkspace)

    log(s"Discovered ${discoveredExamples.size} examples: ${discoveredExamples.map(_.name).mkString(", ")}")

    val loaded = loadSelectedExamples()
    log(s"Loaded ${loaded.size} examples for testing")
    loaded
  }

  override def afterAll(): Unit = {
    if os.exists(testWorkspace) && cleanupWorkspace then
      log(s"Cleaning up test workspace: $testWorkspace")
      os.remove.all(testWorkspace)
  }

  /** Finds a compiled classfile for a specific example, Scala version, and class name. */
  def findClassFile(example: LoadedExample, scalaVersion: String, className: String): Option[Path] = {
    example.compilationResult.results.get(scalaVersion).flatMap { versionResult =>
      versionResult.classFiles.find(_.relativePath.endsWith(s"$className.class")).map(_.absolutePath.toNIO)
    }
  }

  /** Patches all classfiles for a given example and version, handling companion pairs.
    *
    * @return
    *   Map from original className to patched file paths
    */
  def patchAllClassFilesForVersion(example: LoadedExample, version: String): Either[String, Map[String, Seq[Path]]] = {
    val versionResult = example.compilationResult.results.get(version)
    if (versionResult.isEmpty || !versionResult.get.success) {
      Left(s"Version $version not successfully compiled")
    } else {
      // Collect all classfiles for this version
      val classFiles = versionResult.get.classFiles

      // Read all classfiles into memory with their class names
      val classfileMap = classFiles.map { cf =>
        val bytes = Files.readAllBytes(cf.absolutePath.toNIO)
        val className = cf.relativePath.stripSuffix(".class").replace("/", ".")
        (className, bytes)
      }.toMap

      // Group classfiles (detect companion pairs)
      LazyValAnalyzer.group(classfileMap).flatMap { groups =>
        // Validate that we can detect versions for all groups (no Unknown or MixedVersions in tests)
        boundary {
          groups.foreach { group =>
            val detectionResult = group match {
              case ClassfileGroup.Single(name, classInfo, _) =>
                LazyValDetector.detect(classInfo, None)
              case ClassfileGroup.CompanionPair(_, _, companionObjectInfo, classInfo, _, _) =>
                LazyValDetector.detect(companionObjectInfo, Some(classInfo))
            }

            detectionResult match {
              case LazyValDetectionResult.NoLazyVals => // OK
              case LazyValDetectionResult.LazyValsFound(lazyVals, ScalaVersion.Unknown) =>
                break(Left(s"Detected Unknown version for ${group.primaryName} compiled with Scala $version. LazyVals: ${lazyVals.map(lv => s"${lv.name} (version=${lv.version})").mkString(", ")}"))
              case LazyValDetectionResult.LazyValsFound(_, _) => // OK
              case LazyValDetectionResult.MixedVersions(lazyVals) =>
                val versionBreakdown = lazyVals.groupBy(_.version).map { case (v, lvs) => s"$v: ${lvs.map(_.name).mkString(", ")}" }.mkString("; ")
                break(Left(s"Detected mixed versions for ${group.primaryName} compiled with Scala $version. Breakdown: $versionBreakdown"))
            }
          }

          // Patch each group
          val resultMap = scala.collection.mutable.Map[String, Seq[Path]]()
          var error: Option[String] = None

          groups.foreach { group =>
            if (error.isEmpty) {
              BytecodePatcher.patch(group) match {
                case BytecodePatcher.PatchResult.PatchedSingle(name, bytes) =>
                  // Write single patched file
                  val patchedPath = writePatchedFile(name, bytes, example, version)
                  resultMap(name) = Seq(patchedPath)

                case BytecodePatcher.PatchResult
                      .PatchedPair(companionObjectName, className, companionObjectBytes, classBytes) =>
                  // Write both patched files
                  val objectPath = writePatchedFile(companionObjectName, companionObjectBytes, example, version)
                  val classPath = writePatchedFile(className, classBytes, example, version)
                  resultMap(companionObjectName) = Seq(objectPath)
                  resultMap(className) = Seq(classPath)

                case BytecodePatcher.PatchResult.NotApplicable =>
                  // Skip, no patching needed
                  ()

                case BytecodePatcher.PatchResult.Failed(err) =>
                  error = Some(s"Patching failed for group ${group.primaryName}: $err")
              }
            }
          }

          error match {
            case Some(err) => Left(err)
            case None      => Right(resultMap.toMap)
          }
        }
      }
    }
  }

  /** Writes a patched classfile to the workspace. */
  private def writePatchedFile(className: String, bytes: Array[Byte], example: LoadedExample, version: String): Path = {
    val relativePath = className.replace('.', '/') + ".class"
    val patchedPath = patchedWorkspace / example.name / version / os.RelPath(relativePath)

    os.makeDir.all(patchedPath / os.up)
    os.write.over(patchedPath, bytes)

    patchedPath.toNIO
  }

  /** Gets the full classpath for a compiled example (includes Scala library).
    *
    * @param targetDir
    *   The directory containing the compiled Scala code
    * @param scalaVersion
    *   The Scala version used for compilation
    * @return
    *   Full classpath string including Scala library and dependencies
    */
  def getScalaCliClasspath(targetDir: os.Path, scalaVersion: String): String = {
    val result = os
      .proc("scala-cli", "compile", "--print-classpath", "--jvm", "17", "-S", scalaVersion, targetDir.toString)
      .call(cwd = targetDir, stderr = os.Pipe, stdout = os.Pipe)

    result.out.text().trim
  }

  /** Runs a classfile with java and captures stdout and stderr.
    *
    * @param outputDir
    *   Directory containing compiled class files
    * @param scalaLibClasspath
    *   Classpath for Scala library and dependencies
    * @param mainClass
    *   Main class to run
    * @return
    *   (exitCode, stdout, stderr)
    */
  def runWithJava(outputDir: os.Path, scalaLibClasspath: String, mainClass: String): (Int, String, String) = {
    // Combine the output directory with the Scala library classpath
    val fullClasspath = s"${outputDir}:${scalaLibClasspath}"

    val result = os
      .proc("java", "-cp", fullClasspath, mainClass)
      .call(check = false, stderr = os.Pipe)

    (result.exitCode, result.out.text().trim, result.err.text().trim)
  }

  /** Test: Patch all 3.3-3.7 classfiles and verify semantic equivalence with 3.8 */
  test("Patch 3.3-3.7 bytecode and verify semantic equivalence with 3.8") {
    examples.foreach { example =>
      // Skip examples without lazy vals
      if (example.metadata.expectedClasses.exists(_.lazyVals.nonEmpty)) {
        log(s"\n[${example.name}] Testing bytecode patching")

        example.metadata.expectedClasses.filter(_.lazyVals.nonEmpty).foreach { expectedClass =>
          val className = expectedClass.className

          // Get 3.8 reference classfile
          val ref38File = findClassFile(example, targetVersion, className)
          assert(ref38File.isDefined, s"3.8 reference classfile not found for $className")

          val ref38Bytes = Files.readAllBytes(ref38File.get)
          val ref38ClassInfo = ClassfileParser.parse(ref38Bytes).toOption.get

          patchableVersions.foreach { version =>
            // Patch all classfiles for this version (handles companion pairs)
            patchAllClassFilesForVersion(example, version) match {
              case Right(patchedFilesMap) =>
                // Look up patched files for this className
                patchedFilesMap.get(className) match {
                  case Some(patchedFiles) =>
                    log(s"  Testing $version → 3.8 transformation for $className")
                    log(s"    ✓ Patched successfully (${patchedFiles.size} file(s))")

                    // Parse the main patched classfile (first one for this className)
                    val patchedBytes = Files.readAllBytes(patchedFiles.head)
                    val patchedClassInfo = ClassfileParser.parse(patchedBytes).toOption.get

                    // Semantic comparison with 3.8 reference
                    val comparison = SemanticLazyValComparator.compare(patchedClassInfo, ref38ClassInfo)

                    if !comparison.areIdentical then
                      // Inspect bytecode on failure - show both the patched version and the 3.8 reference
                      inspectOnFailure(example, version, s"Patching semantic comparison failure: Expected patched $version to be identical to 3.8 for $className, but got: $comparison")
                      inspectOnFailure(example, targetVersion, s"Patching semantic comparison failure: 3.8 reference for $className")
                      fail(s"Patched $version bytecode should be semantically identical to 3.8 for $className, but got: $comparison")

                    log(s"    ✓ Semantically identical to 3.8")

                  case None =>
                    log(s"  ⊘ Skipping $version (not patched)")
                }

              case Left(error) =>
                fail(s"Failed to patch $className from $version: $error")
            }
          }
        }
      }
    }
  }

  /** Test: Runtime verification - Unsafe warnings and correct output */
  test("Runtime verification: Unsafe warnings and correct output") {
    examples.foreach { example =>
      // Only test examples with lazy vals AND expected output
      if (example.metadata.expectedClasses.exists(_.lazyVals.nonEmpty) && example.metadata.expectedOutput.isDefined) {
        log(s"\n[${example.name}] Testing runtime behavior")

        val expectedOutput = example.metadata.expectedOutput.get
        val mainClassName = example.metadata.mainClassName

        // Test pre-patched versions (3.3-3.7) - should have Unsafe warning
        patchableVersions.foreach { version =>
          example.compilationResult.results.get(version).foreach { versionResult =>
            if (versionResult.success) {
              log(s"  Testing pre-patched $version runtime")

              val outputDir = os.Path(versionResult.classFiles.head.absolutePath.toNIO.getParent)
              val targetDir = testWorkspace / example.name / version
              val scalaLibClasspath = getScalaCliClasspath(targetDir, version)
              val (exitCode, stdout, stderr) = runWithJava(outputDir, scalaLibClasspath, mainClassName)

              // Verify correct output
              assertEquals(
                stdout,
                expectedOutput,
                s"Pre-patched $version should produce correct output"
              )

              // Verify Unsafe warning is present
              assert(
                stderr.contains("WARNING") && stderr.contains("sun.misc.Unsafe") && stderr
                  .contains("scala.runtime.LazyVals"),
                s"Pre-patched $version should have Unsafe warning in stderr, but got: $stderr"
              )

              log(s"    ✓ Correct output and Unsafe warning present")
            }
          }
        }

        // Test patched versions (3.3-3.7 → 3.8) - should NOT have Unsafe warning
        patchableVersions.foreach { version =>
          patchAllClassFilesForVersion(example, version) match {
            case Right(patchedFilesMap) if patchedFilesMap.nonEmpty =>
              log(s"  Testing patched $version runtime")

              // Use the patched workspace root for this example/version (not the file's parent,
              // which would be wrong for classes in packages like foo.package$)
              val outputDir = patchedWorkspace / example.name / version
              val targetDir = testWorkspace / example.name / version
              val scalaLibClasspath = getScalaCliClasspath(targetDir, version)
              val (exitCode, stdout, stderr) = runWithJava(outputDir, scalaLibClasspath, mainClassName)

              // Verify correct output
              assertEquals(
                stdout,
                expectedOutput,
                s"Patched $version should produce correct output"
              )

              // Verify NO Unsafe warning
              assert(
                !stderr.contains("sun.misc.Unsafe"),
                s"[${example.name}] Patched $version should NOT have Unsafe warning in stderr, but got: $stderr"
              )

              log(s"    ✓ Correct output and NO Unsafe warning")

            case Left(error) =>
              fail(s"[${example.name}/$version] Failed to patch for runtime testing: $error")

            case Right(_) =>
              log(s"  ⊘ No files patched for $version")
          }
        }

        // Test 3.8 reference - should NOT have Unsafe warning
        example.compilationResult.results.get(targetVersion).foreach { versionResult =>
          if (versionResult.success) {
            log(s"  Testing 3.8 reference runtime")

            val outputDir = os.Path(versionResult.classFiles.head.absolutePath.toNIO.getParent)
            val targetDir = testWorkspace / example.name / targetVersion
            val scalaLibClasspath = getScalaCliClasspath(targetDir, targetVersion)
            val (exitCode, stdout, stderr) = runWithJava(outputDir, scalaLibClasspath, mainClassName)

            // Verify correct output
            assertEquals(
              stdout,
              expectedOutput,
              s"3.8 reference should produce correct output"
            )

            // Verify NO Unsafe warning
            assert(
              !stderr.contains("sun.misc.Unsafe"),
              s"3.8 reference should NOT have Unsafe warning in stderr, but got: $stderr"
            )

            log(s"    ✓ Correct output and NO Unsafe warning")
          }
        }
      }
    }
  }

  /** Test: Verify patching is idempotent (patching patched bytecode should be no-op) */
  test("Patching is idempotent") {
    examples.foreach { example =>
      if (example.metadata.expectedClasses.exists(_.lazyVals.nonEmpty)) {
        log(s"\n[${example.name}] Testing idempotency")

        patchableVersions.headOption.foreach { version =>
          patchAllClassFilesForVersion(example, version) match {
            case Right(patchedFilesMap) if patchedFilesMap.nonEmpty =>
              // Try patching the patched files again - read them back and group
              val repatchClassfileMap = patchedFilesMap.flatMap { case (className, paths) =>
                paths.map { path =>
                  val bytes = Files.readAllBytes(path)
                  (className, bytes)
                }
              }

              LazyValAnalyzer.group(repatchClassfileMap) match {
                case Right(groups) =>
                  groups.foreach { group =>
                    BytecodePatcher.patch(group) match {
                      case BytecodePatcher.PatchResult.NotApplicable =>
                        log(s"  ✓ Patched bytecode correctly returns NotApplicable for ${group.primaryName}")

                      case other =>
                        fail(s"Patching already-patched bytecode should return NotApplicable, got: $other")
                    }
                  }

                case Left(error) =>
                  fail(s"Failed to group patched files: $error")
              }

            case Left(error) =>
              fail(s"Failed initial patching: $error")

            case Right(_) =>
              log(s"  ⊘ No files to test idempotency")
          }
        }
      }
    }
  }

  /** Test: Non-patchable versions return NotApplicable */
  test("Non-patchable versions return NotApplicable") {
    val nonPatchableVersions = Set("3.0.2", "3.1.3", "3.2.2", targetVersion)

    examples.foreach { example =>
      if (example.metadata.expectedClasses.exists(_.lazyVals.nonEmpty)) {
        log(s"\n[${example.name}] Testing non-patchable versions")

        nonPatchableVersions.foreach { version =>
          example.compilationResult.results.get(version).foreach { versionResult =>
            if (versionResult.success) {
              // Read all classfiles for this version
              val classfileMap = versionResult.classFiles.map { cf =>
                val bytes = Files.readAllBytes(cf.absolutePath.toNIO)
                val className = cf.relativePath.stripSuffix(".class").replace("/", ".")
                (className, bytes)
              }.toMap

              // Group and patch
              LazyValAnalyzer.group(classfileMap) match {
                case Right(groups) =>
                  groups.foreach { group =>
                    BytecodePatcher.patch(group) match {
                      case BytecodePatcher.PatchResult.NotApplicable =>
                        log(s"  ✓ $version correctly returns NotApplicable for ${group.primaryName}")

                      case other =>
                        fail(s"$version should return NotApplicable, got: $other")
                    }
                  }

                case Left(error) =>
                  fail(s"Failed to group classfiles for $version: $error")
              }
            }
          }
        }
      }
    }
  }
}
