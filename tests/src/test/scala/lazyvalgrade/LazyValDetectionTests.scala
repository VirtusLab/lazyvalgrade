package lazyvalgrade

import munit.FunSuite
import lazyvalgrade.classfile.ClassfileParser
import lazyvalgrade.lazyval.{LazyValDetector, LazyValDetectionResult, ScalaVersion}
import java.nio.file.{Files, Paths, Path}
import scala.util.Try

/** Test suite for lazy val detection across Scala versions.
  *
  * Compiles test fixtures using ExampleRunner and verifies that the correct lazy val implementation is detected.
  */
class LazyValDetectionTests extends FunSuite {

  /** Scala versions to test with their expected lazy val implementations */
  val testVersions: Seq[(String, ScalaVersion)] = Seq(
    ("3.0.2", ScalaVersion.Scala30x_31x),
    ("3.1.3", ScalaVersion.Scala30x_31x),
    ("3.2.2", ScalaVersion.Scala32x),
    ("3.3.0", ScalaVersion.Scala33x_37x),
    ("3.3.6", ScalaVersion.Scala33x_37x),
    ("3.4.3", ScalaVersion.Scala33x_37x),
    ("3.5.2", ScalaVersion.Scala33x_37x),
    ("3.6.4", ScalaVersion.Scala33x_37x),
    ("3.7.3", ScalaVersion.Scala33x_37x)
  )

  /** Compilation result from ExampleRunner */
  var compilationResult: Option[ExampleCompilationResult] = None

  /** Temporary workspace for test compilations */
  val testWorkspace: os.Path = os.temp.dir(prefix = "lazyvalgrade-tests-", deleteOnExit = false)

  override def beforeAll(): Unit = {
    // Set up paths
    val fixturesDir = os.pwd / "tests" / "src" / "test" / "resources" / "fixtures"
    val examplesDir = fixturesDir / "examples"
    val examplePath = examplesDir / "simple-lazy-val"

    println(s"Fixtures directory: $fixturesDir")
    println(s"Examples directory: $examplesDir")
    println(s"Example path: $examplePath")
    println(s"Test workspace: $testWorkspace")

    // Create ExampleRunner
    val runner = ExampleRunner(examplesDir, testWorkspace)

    // Compile the example with all test versions
    val scalaVersions = testVersions.map(_._1).toSet
    println(s"Compiling with Scala versions: ${scalaVersions.mkString(", ")}")

    val result = runner.runExample(examplePath, scalaVersions)
    result match {
      case Right(res) =>
        compilationResult = Some(res)
        val successful = res.successfulResults.size
        val total = res.results.size
        println(s"Compilation complete: $successful/$total versions succeeded")

        // Print details about failed compilations
        res.results.foreach { case (version, versionResult) =>
          if !versionResult.success then
            println(s"  ✗ Scala $version failed: ${versionResult.error.getOrElse("unknown error")}")
        }

      case Left(error) =>
        println(s"Failed to compile examples: $error")
        throw new RuntimeException(s"Failed to compile test fixtures: $error")
    }
  }

  override def afterAll(): Unit = {
    // Clean up workspace
    if os.exists(testWorkspace) then
      println(s"Cleaning up test workspace: $testWorkspace")
      os.remove.all(testWorkspace)
  }

  /** Finds a compiled classfile for a specific Scala version.
    *
    * @param scalaVersion
    *   The Scala version
    * @return
    *   Path to SimpleLazyVal$.class if found
    */
  def findClassFile(scalaVersion: String): Option[Path] = {
    compilationResult.flatMap { result =>
      result.results.get(scalaVersion).flatMap { versionResult =>
        versionResult.classFiles
          .find(_.relativePath.endsWith("SimpleLazyVal$.class"))
          .map(_.absolutePath.toNIO)
      }
    }
  }

  /** Tests lazy val detection for a specific Scala version */
  def testVersion(scalaVersion: String, expectedVersion: ScalaVersion): Unit = {
    test(s"Detect lazy val implementation in Scala $scalaVersion") {
      // Check if this version compiled successfully
      val wasCompiled = compilationResult.exists { result =>
        result.results.get(scalaVersion).exists(_.success)
      }

      // Skip if compilation failed (likely due to JDK compatibility)
      assert(wasCompiled, s"Scala $scalaVersion did not compile (likely JDK compatibility issue)")

      val classFileOpt = findClassFile(scalaVersion)

      assert(classFileOpt.isDefined, s"Failed to find compiled classfile for Scala $scalaVersion")

      val classFile = classFileOpt.get
      val bytes = Files.readAllBytes(classFile)

      // Parse classfile
      val parseResult = ClassfileParser.parse(bytes)
      assert(parseResult.isRight, s"Failed to parse classfile: ${parseResult.left.getOrElse("unknown error")}")

      val classInfo = parseResult.getOrElse(???)

      // Detect lazy vals
      val detector = LazyValDetector()
      val detectionResult = detector.detect(classInfo)

      // Verify we found lazy vals
      detectionResult match {
        case LazyValDetectionResult.NoLazyVals =>
          fail("Expected to find lazy vals but found none")

        case LazyValDetectionResult.LazyValsFound(lazyVals, version) =>
          assertEquals(lazyVals.size, 1, "Expected exactly 1 lazy val")
          assertEquals(lazyVals.head.name, "simpleLazy", "Expected lazy val named 'simpleLazy'")
          assertEquals(version, expectedVersion, s"Expected $expectedVersion but detected $version")

          // Additional version-specific checks
          expectedVersion match {
            case ScalaVersion.Scala30x_31x | ScalaVersion.Scala32x =>
              assert(lazyVals.head.bitmapField.isDefined, "Expected bitmap field for 3.0-3.2.x")
              assert(lazyVals.head.initMethod.isEmpty, "Did not expect lzyINIT method for 3.0-3.2.x")
              assert(
                lazyVals.head.storageField.descriptor != "Ljava/lang/Object;",
                "Expected typed storage field for 3.0-3.2.x"
              )

              // Check accessor instruction count
              val accessorSize = lazyVals.head.accessorMethod.map(_.instructions.size).getOrElse(0)
              expectedVersion match {
                case ScalaVersion.Scala30x_31x =>
                  assertEquals(accessorSize, 94, "Expected 94 instructions in accessor for 3.0.x/3.1.x")
                case ScalaVersion.Scala32x =>
                  assertEquals(accessorSize, 88, "Expected 88 instructions in accessor for 3.2.x")
                case _ => ()
              }

            case ScalaVersion.Scala33x_37x =>
              assert(lazyVals.head.bitmapField.isEmpty, "Did not expect bitmap field for 3.3-3.7.x")
              assert(lazyVals.head.initMethod.isDefined, "Expected lzyINIT method for 3.3-3.7.x")
              assert(lazyVals.head.offsetField.isDefined, "Expected OFFSET field for 3.3-3.7.x")
              assertEquals(
                lazyVals.head.storageField.descriptor,
                "Ljava/lang/Object;",
                "Expected Object storage field for 3.3-3.7.x"
              )

              // Check accessor instruction count (should be 26 for all 3.3-3.7.x)
              val accessorSize = lazyVals.head.accessorMethod.map(_.instructions.size).getOrElse(0)
              assertEquals(accessorSize, 26, "Expected 26 instructions in accessor for 3.3-3.7.x")

            case _ => ()
          }

        case LazyValDetectionResult.MixedVersions(lazyVals) =>
          fail(s"Unexpected mixed versions: ${lazyVals.map(_.version).distinct}")
      }
    }
  }

  // Generate tests for all versions (tests will skip if compilation failed)
  testVersions.foreach { case (version, expectedVersion) =>
    testVersion(version, expectedVersion)
  }

  test("Verify version classification logic") {
    // Test that version enums have correct properties
    assert(ScalaVersion.Scala30x_31x.isLegacy)
    assert(ScalaVersion.Scala32x.isLegacy)
    assert(ScalaVersion.Scala33x_37x.isLegacy)
    assert(!ScalaVersion.Scala38Plus.isLegacy)

    assert(ScalaVersion.Scala30x_31x.isBitmapBased)
    assert(ScalaVersion.Scala32x.isBitmapBased)
    assert(!ScalaVersion.Scala33x_37x.isBitmapBased)

    assert(ScalaVersion.Scala30x_31x.needsTransformation)
    assert(ScalaVersion.Scala32x.needsTransformation)
    assert(ScalaVersion.Scala33x_37x.needsTransformation)
    assert(!ScalaVersion.Scala38Plus.needsTransformation)
  }
}
