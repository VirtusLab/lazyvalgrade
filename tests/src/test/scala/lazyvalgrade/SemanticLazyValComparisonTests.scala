package lazyvalgrade

import munit.FunSuite
import lazyvalgrade.classfile.ClassfileParser
import lazyvalgrade.lazyval.{SemanticLazyValComparator, SemanticLazyValComparisonResult}
import java.nio.file.{Files, Path}
import scala.collection.immutable.TreeSet

/** Test suite for semantic lazy val comparison across Scala versions.
  *
  * Tests the version-agnostic comparison that answers: "Are the lazy val implementations identical?"
  *
  * Expected behavior:
  *   - 3.0 vs 3.1: IDENTICAL (both use bitmap-based with getOffset, 94 instructions)
  *   - 3.1 vs 3.2: DIFFERENT (3.1 uses getOffset with 94 instructions, 3.2 uses getDeclaredField with 88)
  *   - 3.2 vs 3.3: DIFFERENT (bitmap-based vs object-based)
  *   - 3.3 vs 3.7: IDENTICAL (both use object-based with Unsafe)
  *   - 3.7 vs 3.8: DIFFERENT (Unsafe vs VarHandle)
  *
  * Use SELECT_EXAMPLE environment variable to filter examples:
  *   - SELECT_EXAMPLE=simple-lazy-val (single example)
  *   - SELECT_EXAMPLE=simple-lazy-val,class-lazy-val (multiple examples)
  */
class SemanticLazyValComparisonTests extends FunSuite with ExampleLoader {

  // ===== ExampleLoader implementation =====

  override val examplesDir: os.Path = os.pwd / "tests" / "src" / "test" / "resources" / "fixtures" / "examples"
  override val testWorkspace: os.Path = os.temp.dir(prefix = "lazyvalgrade-semantic-tests-", deleteOnExit = false)
  override val quietCompilation: Boolean = true

  /** Helper to print only when not in quiet mode */
  private def log(msg: => String): Unit = if !quietTests then println(msg)

  /** Test version pairs with expected comparison results */
  val versionPairs: Seq[(String, String, Boolean)] = Seq(
    // (version1, version2, shouldBeIdentical)

    // Within-family comparisons
    ("3.0.2", "3.1.3", true), // Same pattern: bitmap-based with getOffset
    ("3.1.3", "3.2.2", false), // Different: 94 vs 88 instructions, different clinit
    ("3.3.0", "3.3.6", true), // Same pattern: object-based Unsafe
    ("3.3.6", "3.4.3", true), // Same pattern: object-based Unsafe
    ("3.4.3", "3.5.2", true), // Same pattern: object-based Unsafe
    ("3.5.2", "3.6.4", true), // Same pattern: object-based Unsafe
    ("3.6.4", "3.7.3", true), // Same pattern: object-based Unsafe
    ("3.3.0", "3.7.3", true), // Sanity check: 3.3 LTS vs 3.7 latest pre-3.8

    // Cross-family comparisons (pre-3.3 to 3.3+)
    ("3.2.2", "3.3.0", false), // Different: bitmap-based vs object-based
    ("3.0.2", "3.3.0", false), // Different: bitmap-based vs object-based
    ("3.1.3", "3.3.6", false), // Different: bitmap-based vs object-based

    // All pre-3.8 versions vs 3.8 (all should be different)
    ("3.0.2", "3.8.1", false), // Different: bitmap vs VarHandle
    ("3.1.3", "3.8.1", false), // Different: bitmap vs VarHandle
    ("3.2.2", "3.8.1", false), // Different: bitmap vs VarHandle
    ("3.3.0", "3.8.1", false), // Different: Unsafe vs VarHandle
    ("3.3.6", "3.8.1", false), // Different: Unsafe vs VarHandle
    ("3.4.3", "3.8.1", false), // Different: Unsafe vs VarHandle
    ("3.5.2", "3.8.1", false), // Different: Unsafe vs VarHandle
    ("3.6.4", "3.8.1", false), // Different: Unsafe vs VarHandle
    ("3.7.3", "3.8.1", false) // Different: Unsafe vs VarHandle
  )

  override def requiredScalaVersions: Seq[String] =
    (versionPairs.flatMap { case (v1, v2, _) => Seq(v1, v2) }).distinct

  // ===== Loaded examples =====

  /** All loaded examples with their test data (respects SELECT_EXAMPLE filter) */
  lazy val examples: Seq[LoadedExample] = {
    log(s"Semantic comparison test workspace: $testWorkspace")
    loadSelectedExamples()
  }

  /** Finds a compiled classfile for a specific example, Scala version, and class name. */
  def findClassFile(example: LoadedExample, scalaVersion: String, className: String): Option[Path] = {
    example.compilationResult.results.get(scalaVersion).flatMap { versionResult =>
      versionResult.classFiles
        .find(_.relativePath.endsWith(s"$className.class"))
        .map(_.absolutePath.toNIO)
    }
  }

  /** Generate comparison tests for each example and version pair */
  def generateComparisonTests(): Unit = {
    examples.foreach { example =>
      // Only test classes that have lazy vals
      val classesWithLazyVals = example.metadata.expectedClasses.filter(_.lazyVals.nonEmpty)

      classesWithLazyVals.foreach { expectedClass =>
        versionPairs.foreach { case (version1, version2, shouldBeIdentical) =>
          test(s"[${example.name}] ${expectedClass.className}: Scala $version1 vs $version2 ${
              if shouldBeIdentical then "IDENTICAL" else "DIFFERENT"
            }") {
            // Check if both versions compiled
            val compiled1 = example.compilationResult.results.get(version1).exists(_.success)
            val compiled2 = example.compilationResult.results.get(version2).exists(_.success)

            assert(compiled1, s"Scala $version1 did not compile")
            assert(compiled2, s"Scala $version2 did not compile")

            // Find classfiles
            val classFile1Opt = findClassFile(example, version1, expectedClass.className)
            val classFile2Opt = findClassFile(example, version2, expectedClass.className)

            assert(classFile1Opt.isDefined, s"Could not find ${expectedClass.className}.class for Scala $version1")
            assert(classFile2Opt.isDefined, s"Could not find ${expectedClass.className}.class for Scala $version2")

            val bytes1 = Files.readAllBytes(classFile1Opt.get)
            val bytes2 = Files.readAllBytes(classFile2Opt.get)

            // Parse classfiles
            val class1 = ClassfileParser.parse(bytes1).getOrElse {
              fail(s"Failed to parse classfile for Scala $version1")
            }
            val class2 = ClassfileParser.parse(bytes2).getOrElse {
              fail(s"Failed to parse classfile for Scala $version2")
            }

            // Check if we need companion classes (for module classes ending with $)
            val companion1Opt = if expectedClass.className.endsWith("$") then
              val companionClassName = expectedClass.className.dropRight(1)
              findClassFile(example, version1, companionClassName).flatMap { companionPath =>
                ClassfileParser.parse(Files.readAllBytes(companionPath)).toOption
              }
            else None

            val companion2Opt = if expectedClass.className.endsWith("$") then
              val companionClassName = expectedClass.className.dropRight(1)
              findClassFile(example, version2, companionClassName).flatMap { companionPath =>
                ClassfileParser.parse(Files.readAllBytes(companionPath)).toOption
              }
            else None

            // Perform semantic comparison
            val result = SemanticLazyValComparator.compare(class1, class2, companion1Opt, companion2Opt)

            // Verify expected result
            if shouldBeIdentical then
              if !result.areIdentical then
                // Inspect bytecode on failure
                inspectOnFailure(example, version1, s"Semantic comparison failure: Expected IDENTICAL but got $result")
                inspectOnFailure(example, version2, s"Semantic comparison failure: Expected IDENTICAL but got $result")
                fail(s"Expected IDENTICAL but got: $result")
            else
              if result.areIdentical then
                // Inspect bytecode on failure
                inspectOnFailure(example, version1, s"Semantic comparison failure: Expected DIFFERENT but got IDENTICAL")
                inspectOnFailure(example, version2, s"Semantic comparison failure: Expected DIFFERENT but got IDENTICAL")
                fail(s"Expected DIFFERENT but got IDENTICAL")

            // Print detailed results for debugging
            result match {
              case SemanticLazyValComparisonResult.Identical =>
                log(s"  ✓ Lazy vals are identical for Scala $version1 and $version2")

              case SemanticLazyValComparisonResult.Different(reasons) =>
                log(s"  ✗ Lazy vals differ for Scala $version1 and $version2:")
                reasons.foreach { reason =>
                  log(s"    - $reason")
                }

              case other =>
                log(s"  Result for Scala $version1 and $version2: $other")
            }
          }
        }
      }
    }
  }

  // Generate all comparison tests
  generateComparisonTests()

  // Additional edge case tests
  test("No lazy vals: should be trivially identical") {
    // Use discoveredExamples to find the no-lazy-val example without triggering compilation
    val noLazyValDiscovered = discoveredExamples.find(_.metadata.expectedClasses.exists(_.lazyVals.isEmpty))

    if (noLazyValDiscovered.isEmpty) {
      throw new RuntimeException("No example with no lazy vals found in metadata")
    }

    // Load only the no-lazy-val example
    val example = loadExample(noLazyValDiscovered.get.name).getOrElse {
      throw new RuntimeException(s"Failed to compile example '${noLazyValDiscovered.get.name}'")
    }

    // Find or create a class without lazy vals
    val classesWithoutLazyVals = example.metadata.expectedClasses.filter(_.lazyVals.isEmpty)

    if classesWithoutLazyVals.nonEmpty then
      val className = classesWithoutLazyVals.head.className

      val version1 = "3.3.0"
      val version2 = "3.7.3"

      val classFile1Opt = findClassFile(example, version1, className)
      val classFile2Opt = findClassFile(example, version2, className)

      if classFile1Opt.isDefined && classFile2Opt.isDefined then
        val bytes1 = Files.readAllBytes(classFile1Opt.get)
        val bytes2 = Files.readAllBytes(classFile2Opt.get)

        val class1 = ClassfileParser.parse(bytes1).toOption.get
        val class2 = ClassfileParser.parse(bytes2).toOption.get

        val result = SemanticLazyValComparator.compare(class1, class2)

        assert(result.areIdentical, "Classes without lazy vals should be trivially identical")
        assert(
          result == SemanticLazyValComparisonResult.BothNoLazyVals,
          "Expected BothNoLazyVals result"
        )
  }

  test("Same class, same version: should be identical") {
    val example = examples.find(_.metadata.expectedClasses.exists(_.lazyVals.nonEmpty)).getOrElse {
      throw new RuntimeException("No example with lazy vals found")
    }
    val expectedClass = example.metadata.expectedClasses.find(_.lazyVals.nonEmpty).get

    // Test all successfully compiled versions
    val allVersions = versionPairs.flatMap { case (v1, v2, _) => Seq(v1, v2) }.distinct

    allVersions.foreach { version =>
      val classFileOpt = findClassFile(example, version, expectedClass.className)

      if classFileOpt.isDefined then
        val bytes = Files.readAllBytes(classFileOpt.get)
        val classInfo = ClassfileParser.parse(bytes).toOption.get

        // Compare with itself
        val result = SemanticLazyValComparator.compare(classInfo, classInfo)

        assert(
          result.areIdentical,
          s"A class should be identical to itself (Scala $version)"
        )
        assertEquals(
          result,
          SemanticLazyValComparisonResult.Identical,
          s"Expected Identical result for Scala $version"
        )
        log(s"  ✓ Scala $version: identical to itself")
      else log(s"  ⊘ Scala $version: skipped (not compiled)")
    }
  }

  test("Verify key differences are detected") {
    // This test ensures that the comparison logic actually detects differences
    // between different Scala version families

    val example = examples.find(_.metadata.expectedClasses.exists(_.lazyVals.nonEmpty)).getOrElse {
      throw new RuntimeException("No example with lazy vals found")
    }
    val expectedClass = example.metadata.expectedClasses.find(_.lazyVals.nonEmpty).get

    // Test 2 kinds of bitmap-based (3.1, 3.2) vs object-based (3.3)
    val classFile31Opt = findClassFile(example, "3.1.3", expectedClass.className)
    val classFile32Opt = findClassFile(example, "3.2.2", expectedClass.className)
    val classFile33Opt = findClassFile(example, "3.3.0", expectedClass.className)

    assert(
      classFile31Opt.isDefined && classFile32Opt.isDefined && classFile33Opt.isDefined,
      "Required classfiles not available"
    )

    val bytes31 = Files.readAllBytes(classFile31Opt.get)
    val bytes32 = Files.readAllBytes(classFile32Opt.get)
    val bytes33 = Files.readAllBytes(classFile33Opt.get)

    val class31 = ClassfileParser.parse(bytes31).toOption.get
    val class32 = ClassfileParser.parse(bytes32).toOption.get
    val class33 = ClassfileParser.parse(bytes33).toOption.get

    val result3132 = SemanticLazyValComparator.compare(class31, class32)
    val result3233 = SemanticLazyValComparator.compare(class32, class33)
    val result3133 = SemanticLazyValComparator.compare(class31, class33)

    assert(!result3132.areIdentical, "3.1 vs 3.2 should be different")
    assert(!result3233.areIdentical, "3.2 vs 3.3 should be different")
    assert(!result3133.areIdentical, "3.1 vs 3.3 should be different")

    List(result3132, result3233, result3133).foreach {
      case SemanticLazyValComparisonResult.Different(reasons) =>
        // Should have at least one reason mentioning the difference
        assert(reasons.nonEmpty, "Should have reasons for the difference")
        log(s"  Detected differences: ${reasons.mkString(", ")}")

      case _ =>
        fail(s"Expected Different result, got: $result3233")
    }
  }
}
