package lazyvalgrade

import scala.collection.immutable.TreeSet

/** Trait that provides example discovery and loading functionality for tests.
  *
  * This trait manages a two-step process:
  * 1. Discovery: Load all example metadata without compilation
  * 2. Loading: Compile specific examples as needed
  *
  * Use the SELECT_EXAMPLE environment variable to filter examples:
  *   - SELECT_EXAMPLE=simple-lazy-val (single example)
  *   - SELECT_EXAMPLE=simple-lazy-val,class-lazy-val (multiple examples)
  *   - (not set) - all examples available
  */
trait ExampleLoader {

  /** Information about a discovered example (metadata only, not compiled) */
  case class DiscoveredExample(
      name: String,
      path: os.Path,
      metadata: ExampleMetadata
  )

  /** Information about a loaded example (metadata + compiled bytecode) */
  case class LoadedExample(
      name: String,
      path: os.Path,
      metadata: ExampleMetadata,
      compilationResult: ExampleCompilationResult
  )

  // ===== Abstract members that must be provided by implementing classes =====

  /** The Scala versions needed for compilation */
  def requiredScalaVersions: Seq[String]

  /** The examples directory path */
  def examplesDir: os.Path

  /** The test workspace directory for compilation output */
  def testWorkspace: os.Path

  /** Whether to print detailed compilation output */
  def quietCompilation: Boolean = true

  // ===== Discovery phase: Load metadata for all examples =====

  /** All discovered examples with their metadata (no compilation yet).
    *
    * This is always loaded regardless of SELECT_EXAMPLE filter,
    * allowing tests to query what examples exist without triggering compilation.
    */
  lazy val discoveredExamples: Seq[DiscoveredExample] = {
    val allExampleDirs = os.list(examplesDir).filter(os.isDir).toSeq

    allExampleDirs.flatMap { examplePath =>
      val exampleName = examplePath.last
      val metadataPath = examplePath / "metadata.json"

      // Skip directories without metadata.json (like 'tests' directory)
      if (!os.exists(metadataPath)) {
        println(s"Warning: Skipping example '$exampleName': No metadata.json found at $metadataPath")
        None
      } else {
        ExampleMetadata.load(examplePath) match {
          case Left(error) =>
            println(s"Warning: Failed to load metadata for example '$exampleName': $error")
            None

          case Right(metadata) =>
            Some(DiscoveredExample(exampleName, examplePath, metadata))
        }
      }
    }
  }

  /** Get the list of selected example names from the SELECT_EXAMPLE environment variable.
    *
    * @return Some(Set[String]) if SELECT_EXAMPLE is set, None if all examples should be included
    */
  def selectedExamples: Option[Set[String]] = {
    sys.env.get("SELECT_EXAMPLE").map { value =>
      value.split(',').map(_.trim).filter(_.nonEmpty).toSet
    }
  }

  /** Check if an example is selected by the SELECT_EXAMPLE filter.
    *
    * @param exampleName The name of the example
    * @return true if the example should be included (either no filter or explicitly selected)
    */
  def isExampleSelected(exampleName: String): Boolean = {
    selectedExamples match {
      case None => true // No filter - all examples selected
      case Some(selected) => selected.contains(exampleName)
    }
  }

  // ===== Loading phase: Compile examples as needed =====

  /** Create an ExampleRunner for compiling examples */
  private lazy val runner = ExampleRunner(examplesDir, testWorkspace, quiet = quietCompilation)

  /** Load examples matching a predicate, compiling them with the required Scala versions.
    *
    * @param predicate Filter function to select which discovered examples to load
    * @return Loaded examples with compilation results
    */
  def loadExamples(predicate: DiscoveredExample => Boolean): Seq[LoadedExample] = {
    val toLoad = discoveredExamples.filter(predicate)

    if (toLoad.isEmpty) {
      println("Warning: No examples match the provided predicate")
      return Seq.empty
    }

    val versions = requiredScalaVersions.to(TreeSet)
    println(s"Loading ${toLoad.size} example(s) with Scala versions: ${versions.mkString(", ")}")

    toLoad.flatMap { discovered =>
      runner.compileExample(discovered.path, versions) match {
        case Right(compilationResult) =>
          val successful = compilationResult.successfulResults.size
          val total = compilationResult.results.size
          println(s"Example '${discovered.name}': $successful/$total versions compiled")
          Some(LoadedExample(discovered.name, discovered.path, discovered.metadata, compilationResult))

        case Left(error) =>
          println(s"Warning: Failed to compile example '${discovered.name}': $error")
          None
      }
    }
  }

  /** Load a single example by name, compiling it with the required Scala versions.
    *
    * @param exampleName The name of the example to load
    * @return The loaded example, or None if not found or compilation failed
    */
  def loadExample(exampleName: String): Option[LoadedExample] = {
    val results = loadExamples(_.name == exampleName)
    results.headOption
  }

  /** Load examples selected by the SELECT_EXAMPLE environment variable.
    *
    * This is the default loading method that respects the filter.
    * If no filter is set, loads all examples.
    * If a filter is set, loads only the selected examples.
    *
    * @return Loaded examples matching the SELECT_EXAMPLE filter
    */
  def loadSelectedExamples(): Seq[LoadedExample] = {
    selectedExamples match {
      case None =>
        // No filter - load all discovered examples
        loadExamples(_ => true)

      case Some(selected) =>
        // Load only selected examples
        val results = loadExamples(discovered => selected.contains(discovered.name))

        // Warn if any selected examples were not found
        val foundNames = results.map(_.name).toSet
        val notFound = selected.diff(foundNames)
        if (notFound.nonEmpty) {
          println(s"Warning: Selected examples not found or failed to compile: ${notFound.mkString(", ")}")
        }

        if (results.isEmpty) {
          throw new RuntimeException(
            s"No examples matched SELECT_EXAMPLE filter: ${selected.mkString(", ")}\n" +
            s"Available examples: ${discoveredExamples.map(_.name).mkString(", ")}"
          )
        }

        println(s"Filtered to ${results.size} example(s): ${results.map(_.name).mkString(", ")}")
        results
    }
  }
}
