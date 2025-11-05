package lazyvalgrade

import scala.collection.immutable.TreeSet

/** Compiles experimental examples across multiple Scala versions for debugging purposes.
  *
  * This tool:
  * - Takes examples from experimental/examples/
  * - Compiles them with all interesting Scala versions (from LazyValDetectionTests)
  * - Outputs compiled classfiles to .out/ directory for inspection
  */
object CompileExamplesMain {
  import scribe._

  /** Scala versions to test - matches LazyValDetectionTests */
  val testVersions: Seq[String] = Seq(
    "3.0.2",
    "3.1.3",
    "3.2.2",
    "3.3.0",
    "3.3.6",
    "3.4.3",
    "3.5.2",
    "3.6.4",
    "3.7.3",
    "3.8.0-RC1-bin-20251026-5c51b7b-NIGHTLY"
  )

  def main(args: Array[String]): Unit = {
    // Parse command line arguments
    val quiet = args.contains("--quiet") || args.contains("-q")

    // Set up logging
    scribe.Logger.root
      .clearHandlers()
      .clearModifiers()
      .withHandler(minimumLevel = Some(if (quiet) Level.Error else Level.Info))
      .replace()

    if (!quiet) {
      info("=== Experimental Examples Compiler ===")
      info(s"Scala versions: ${testVersions.mkString(", ")}")
    }

    // Set up paths
    val projectRoot = os.pwd
    val examplesRoot = projectRoot / "tests" / "src" / "test" / "resources" / "fixtures" / "examples"
    val outputRoot = projectRoot / ".out"

    if (!quiet) {
      info(s"Examples root: $examplesRoot")
      info(s"Output root: $outputRoot")
    }

    // Create output directory
    os.makeDir.all(outputRoot)

    // Clean output directory if it exists
    if (os.exists(outputRoot)) {
      if (!quiet) info("Cleaning output directory...")
      os.list(outputRoot).foreach(os.remove.all)
    }

    // Validate examples root exists
    if (!os.exists(examplesRoot)) {
      scribe.error(s"Examples directory does not exist: $examplesRoot")
      sys.exit(1)
    }

    // Create ExampleRunner
    val runner = ExampleRunner(examplesRoot, outputRoot, quiet)

    // Discover examples
    val examplesResult = runner.discoverExamples()
    examplesResult match {
      case Left(err) =>
        scribe.error(s"Failed to discover examples: $err")
        sys.exit(1)

      case Right(examples) =>
        if (!quiet) {
          info(s"Discovered ${examples.size} examples:")
          examples.foreach { example =>
            info(s"  - ${example.last}")
          }
        }

        // Compile each example with all versions
        val scalaVersions = testVersions.to(TreeSet)
        val results = runner.compileExamples(examples, scalaVersions)

        // Print summary
        if (!quiet) info("\n=== Compilation Summary ===")
        results.foreach {
          case Right(result) =>
            val successful = result.successfulResults.size
            val total = result.results.size
            if (!quiet) info(s"${result.exampleName}: $successful/$total versions succeeded")

            // Show failures (always show, even in quiet mode)
            result.results.foreach { case (version, versionResult) =>
              if (!versionResult.success) {
                warn(s"  ✗ $version: ${versionResult.error.getOrElse("unknown error")}")
              }
            }

          case Left(err) =>
            scribe.error(s"Failed to compile: $err")
        }

        // Generate javap outputs
        if (!quiet) info("\n=== Generating javap outputs ===")
        results.foreach {
          case Right(result) =>
            result.successfulResults.foreach { case (version, versionResult) =>
              if (!quiet) info(s"Generating javap for ${result.exampleName}/$version...")

              // Find all classfiles for this version
              val versionDir = outputRoot / result.exampleName / version
              val classFiles = os
                .walk(versionDir)
                .filter(p => os.isFile(p) && p.last.endsWith(".class"))
                .filter(p => !p.toString.contains(".bloop"))
                .filter(p => !p.last.contains("$package"))
                .toSeq

              // Generate javap output for each classfile
              classFiles.foreach { classFile =>
                val className = classFile.last.stripSuffix(".class")
                val javapOutput = os.proc("javap", "-v", "-p", classFile.toString)
                  .call(cwd = versionDir, check = false, stderr = os.Pipe, stdout = os.Pipe)

                if (javapOutput.exitCode == 0) {
                  val outputFile = versionDir / s"$className.javap.txt"
                  os.write.over(outputFile, javapOutput.out.text())
                  if (!quiet) debug(s"  Written: ${outputFile.relativeTo(outputRoot)}")
                } else {
                  // Always show javap failures, even in quiet mode
                  warn(s"  Failed to run javap on $className: ${javapOutput.err.text()}")
                }
              }
            }
          case Left(_) => ()
        }

        // Print output directory structure
        if (!quiet) {
          info("\n=== Output Structure ===")
          info(s"Classfiles available in: $outputRoot")
          os.list(outputRoot).foreach { exampleDir =>
            if (os.isDir(exampleDir)) {
              info(s"\n${exampleDir.last}/")
              os.list(exampleDir).foreach { versionDir =>
                if (os.isDir(versionDir)) {
                  val classFiles = os
                    .walk(versionDir)
                    .filter(p => os.isFile(p) && p.last.endsWith(".class"))
                    .filter(p => !p.toString.contains(".bloop"))
                    .filter(p => !p.last.contains("$package"))
                    .size
                  val javapFiles = os.list(versionDir)
                    .filter(p => os.isFile(p) && p.last.endsWith(".javap.txt"))
                    .size
                  info(s"  ${versionDir.last}/ ($classFiles .class files, $javapFiles .javap.txt files)")
                }
              }
            }
          }

          info("\n=== Done ===")
          info("Classfiles compiled and javap outputs generated.")
          info(s"View javap outputs: cat ${outputRoot}/<example>/<version>/*.javap.txt")
        }
    }
  }
}
