package lazyvalgrade

import munit.FunSuite
import lazyvalgrade.classfile.ClassfileParser
import lazyvalgrade.lazyval.{LazyValDetector, LazyValDetectionResult, ScalaVersion, SemanticLazyValComparator}
import lazyvalgrade.patching.BytecodePatcher
import java.nio.file.{Files, Paths, Path}
import scala.util.{Try, Using}
import scala.collection.immutable.TreeSet

/** End-to-end test suite for bytecode patching.
  *
  * Tests the complete patching pipeline:
  *   1. Compile examples with multiple Scala versions (3.0-3.8) 2. Patch 3.3-3.7 bytecode to 3.8 format 3. Semantically
  *      compare patched versions with real 3.8 4. Runtime testing: verify Unsafe warnings and correct output
  */
class BytecodePatchingTests extends FunSuite {

  // Increase timeout for tests that compile multiple Scala versions
  override val munitTimeout = scala.concurrent.duration.Duration(180, "s")

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

  /** Temporary workspace for test compilations */
  val testWorkspace: os.Path = os.temp.dir(prefix = "lazyvalgrade-patching-tests-", deleteOnExit = false)

  /** Patched workspace for transformed classfiles */
  val patchedWorkspace: os.Path = testWorkspace / "patched"

  val cleanupWorkspace: Boolean = false

  case class ExampleTest(
      name: String,
      path: os.Path,
      metadata: ExampleMetadata,
      compilationResult: ExampleCompilationResult
  )

  override def beforeAll(): Unit = {
    // Verify Java version
    val javaVersionOutput = os.proc("java", "--version").call().out.text().trim
    val javaVersionLine = javaVersionOutput.linesIterator.toSeq.headOption.getOrElse("")

    println(s"Java version: $javaVersionLine")

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
        println(s"✓ Using Java $majorVersion")
      case _ =>
        throw new RuntimeException(s"Could not parse Java version from: $javaVersionLine")
    }
  }

  /** Load and compile all examples */
  lazy val examples: Seq[ExampleTest] = {
    val fixturesDir = os.pwd / "tests" / "src" / "test" / "resources" / "fixtures"
    val examplesDir = fixturesDir / "examples"

    println(s"Fixtures directory: $fixturesDir")
    println(s"Examples directory: $examplesDir")
    println(s"Test workspace: $testWorkspace")
    println(s"Patched workspace: $patchedWorkspace")

    os.makeDir.all(patchedWorkspace)

    if (!os.exists(examplesDir)) {
      throw new RuntimeException(s"Examples directory does not exist: $examplesDir")
    }

    val discoveredExamples = os.list(examplesDir).filter(os.isDir).toSeq

    println(s"Discovered ${discoveredExamples.size} examples: ${discoveredExamples.map(_.last).mkString(", ")}")

    val runner = ExampleRunner(examplesDir, testWorkspace)
    val scalaVersions = testVersions.to(TreeSet)

    println(s"Compiling with Scala versions: ${scalaVersions.mkString(", ")}")

    val loadedExamples = discoveredExamples.flatMap { examplePath =>
      val exampleName = examplePath.last

      ExampleMetadata.load(examplePath) match {
        case Left(error) =>
          println(s"Warning: Skipping example '$exampleName': $error")
          None

        case Right(metadata) =>
          println(s"Loading example '$exampleName': ${metadata.description}")

          runner.compileExample(examplePath, scalaVersions) match {
            case Right(compilationResult) =>
              val successful = compilationResult.successfulResults.size
              val total = compilationResult.results.size
              println(s"  Compilation: $successful/$total versions succeeded")

              compilationResult.results.foreach { case (version, versionResult) =>
                if !versionResult.success then
                  println(s"    ✗ Scala $version failed: ${versionResult.error.getOrElse("unknown error")}")
              }

              Some(ExampleTest(exampleName, examplePath, metadata, compilationResult))

            case Left(error) =>
              println(s"Warning: Failed to compile example '$exampleName': $error")
              None
          }
      }
    }

    if (loadedExamples.isEmpty) {
      throw new RuntimeException("No valid examples found with metadata.json files")
    }

    println(s"Loaded ${loadedExamples.size} examples for testing")
    loadedExamples
  }

  override def afterAll(): Unit = {
    if os.exists(testWorkspace) && cleanupWorkspace then
      println(s"Cleaning up test workspace: $testWorkspace")
      os.remove.all(testWorkspace)
  }

  /** Finds a compiled classfile for a specific example, Scala version, and class name. */
  def findClassFile(example: ExampleTest, scalaVersion: String, className: String): Option[Path] = {
    example.compilationResult.results.get(scalaVersion).flatMap { versionResult =>
      versionResult.classFiles.find(_.relativePath.endsWith(s"$className.class")).map(_.absolutePath.toNIO)
    }
  }

  /** Patches a classfile and writes it to the mirrored location in patchedWorkspace. */
  def patchAndWrite(classFile: Path, example: ExampleTest, version: String): Either[String, Path] = {
    // Read original classfile
    val bytes = Files.readAllBytes(classFile)

    // Patch it
    BytecodePatcher.patch(bytes) match {
      case BytecodePatcher.PatchResult.Patched(patchedBytes) =>
        // Create mirrored directory structure
        val relativePathStr = classFile.toString.split(s"/$version/").last
        val relativePath = os.RelPath(relativePathStr)
        val patchedPath = patchedWorkspace / example.name / version / relativePath

        os.makeDir.all(patchedPath / os.up)
        os.write.over(patchedPath, patchedBytes)

        Right(patchedPath.toNIO)

      case BytecodePatcher.PatchResult.NotApplicable =>
        Left("Not applicable for patching")

      case BytecodePatcher.PatchResult.Failed(error) =>
        Left(s"Patching failed: $error")
    }
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
      .call(cwd = targetDir)

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
        println(s"\n[${example.name}] Testing bytecode patching")

        example.metadata.expectedClasses.filter(_.lazyVals.nonEmpty).foreach { expectedClass =>
          val className = expectedClass.className

          // Get 3.8 reference classfile
          val ref38File = findClassFile(example, targetVersion, className)
          assert(ref38File.isDefined, s"3.8 reference classfile not found for $className")

          val ref38Bytes = Files.readAllBytes(ref38File.get)
          val ref38ClassInfo = ClassfileParser.parse(ref38Bytes).toOption.get
          val ref38LazyVals = LazyValDetector.detect(ref38ClassInfo) match {
            case LazyValDetectionResult.LazyValsFound(lvs, _) => lvs
            case _                                            => Seq.empty
          }

          patchableVersions.foreach { version =>
            // Find original classfile
            findClassFile(example, version, className) match {
              case Some(originalFile) =>
                println(s"  Testing $version → 3.8 transformation for $className")

                // Patch it
                patchAndWrite(originalFile, example, version) match {
                  case Right(patchedFile) =>
                    println(s"    ✓ Patched successfully")

                    // Parse patched classfile
                    val patchedBytes = Files.readAllBytes(patchedFile)
                    val patchedClassInfo = ClassfileParser.parse(patchedBytes).toOption.get

                    // Semantic comparison with 3.8 reference
                    val comparison = SemanticLazyValComparator.compare(patchedClassInfo, ref38ClassInfo)

                    assert(
                      comparison.areIdentical,
                      s"Patched $version bytecode should be semantically identical to 3.8 for $className, but got: $comparison"
                    )

                    println(s"    ✓ Semantically identical to 3.8")

                  case Left(error) =>
                    fail(s"Failed to patch $className from $version: $error")
                }

              case None =>
                println(s"  ⊘ Skipping $version (not compiled)")
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
        println(s"\n[${example.name}] Testing runtime behavior")

        val expectedOutput = example.metadata.expectedOutput.get
        val mainClassName = example.metadata.expectedClasses.head.className.stripSuffix("$")

        // Test pre-patched versions (3.3-3.7) - should have Unsafe warning
        patchableVersions.foreach { version =>
          example.compilationResult.results.get(version).foreach { versionResult =>
            if (versionResult.success) {
              println(s"  Testing pre-patched $version runtime")

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

              println(s"    ✓ Correct output and Unsafe warning present")
            }
          }
        }

        // Test patched versions (3.3-3.7 → 3.8) - should NOT have Unsafe warning
        patchableVersions.foreach { version =>
          example.metadata.expectedClasses.filter(_.lazyVals.nonEmpty).foreach { expectedClass =>
            val className = expectedClass.className

            findClassFile(example, version, className).foreach { originalFile =>
              patchAndWrite(originalFile, example, version) match {
                case Right(patchedFile) =>
                  println(s"  Testing patched $version runtime")

                  val outputDir = os.Path(patchedFile.getParent)
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
                    s"Patched $version should NOT have Unsafe warning in stderr, but got: $stderr"
                  )

                  println(s"    ✓ Correct output and NO Unsafe warning")

                case Left(error) =>
                  fail(s"Failed to patch for runtime testing: $error")
              }
            }
          }
        }

        // Test 3.8 reference - should NOT have Unsafe warning
        example.compilationResult.results.get(targetVersion).foreach { versionResult =>
          if (versionResult.success) {
            println(s"  Testing 3.8 reference runtime")

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

            println(s"    ✓ Correct output and NO Unsafe warning")
          }
        }
      }
    }
  }

  /** Test: Verify patching is idempotent (patching patched bytecode should be no-op) */
  test("Patching is idempotent") {
    examples.foreach { example =>
      if (example.metadata.expectedClasses.exists(_.lazyVals.nonEmpty)) {
        println(s"\n[${example.name}] Testing idempotency")

        example.metadata.expectedClasses.filter(_.lazyVals.nonEmpty).foreach { expectedClass =>
          val className = expectedClass.className

          patchableVersions.headOption.foreach { version =>
            findClassFile(example, version, className).foreach { originalFile =>
              patchAndWrite(originalFile, example, version) match {
                case Right(patchedFile) =>
                  // Try patching the patched file
                  val patchedBytes = Files.readAllBytes(patchedFile)

                  BytecodePatcher.patch(patchedBytes) match {
                    case BytecodePatcher.PatchResult.NotApplicable =>
                      println(s"  ✓ Patched bytecode correctly returns NotApplicable")

                    case other =>
                      fail(s"Patching already-patched bytecode should return NotApplicable, got: $other")
                  }

                case Left(error) =>
                  fail(s"Failed initial patching: $error")
              }
            }
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
        println(s"\n[${example.name}] Testing non-patchable versions")

        example.metadata.expectedClasses.filter(_.lazyVals.nonEmpty).foreach { expectedClass =>
          val className = expectedClass.className

          nonPatchableVersions.foreach { version =>
            findClassFile(example, version, className).foreach { classFile =>
              val bytes = Files.readAllBytes(classFile)

              BytecodePatcher.patch(bytes) match {
                case BytecodePatcher.PatchResult.NotApplicable =>
                  println(s"  ✓ $version correctly returns NotApplicable")

                case other =>
                  fail(s"$version should return NotApplicable, got: $other")
              }
            }
          }
        }
      }
    }
  }
}
