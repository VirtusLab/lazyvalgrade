package lazyvalgrade.cli

import lazyvalgrade.classfile.ClassfileParser
import lazyvalgrade.lazyval.{LazyValDetector, LazyValDetectionResult, ScalaVersion}
import lazyvalgrade.patching.BytecodePatcher
import java.nio.file.Files
import scala.util.{Try, Success, Failure}

/** CLI tool for patching Scala 3.x lazy val bytecode to 3.8+ format.
  *
  * Usage: lazyvalgrade <directory>
  *
  * Processes all .class files in the directory (recursively), transforming Scala 3.3-3.7 lazy vals to 3.8+ format.
  */
object Main {

  /** Result of patching a single classfile */
  sealed trait PatchFileResult
  object PatchFileResult {
    case object Patched extends PatchFileResult
    case object NotApplicable extends PatchFileResult
    final case class Failed(error: String) extends PatchFileResult
    final case class Skipped(reason: String) extends PatchFileResult
  }

  /** Summary of patching operations */
  case class PatchSummary(
      total: Int,
      patched: Int,
      notApplicable: Int,
      failed: Int,
      skipped: Int
  ) {
    def successful: Boolean = failed == 0
  }

  def main(args: Array[String]): Unit = {
    if (args.length != 1) {
      Console.err.println(fansi.Color.Red("Error: Expected exactly one argument (directory path)"))
      Console.err.println("Usage: lazyvalgrade <directory>")
      sys.exit(1)
    }

    val targetDir = os.Path(args(0), os.pwd)

    if (!os.exists(targetDir)) {
      Console.err.println(fansi.Color.Red(s"Error: Directory does not exist: $targetDir"))
      sys.exit(1)
    }

    if (!os.isDir(targetDir)) {
      Console.err.println(fansi.Color.Red(s"Error: Not a directory: $targetDir"))
      sys.exit(1)
    }

    println(fansi.Bold.On("LazyValGrade - Scala 3.x Lazy Val Bytecode Patcher"))
    println(fansi.Color.Cyan(s"Processing directory: $targetDir"))
    println()

    // Find all classfiles
    val classFiles = findClassFiles(targetDir)

    if (classFiles.isEmpty) {
      println(fansi.Color.Yellow("No .class files found in directory"))
      sys.exit(0)
    }

    println(fansi.Color.Cyan(s"Found ${classFiles.size} classfile(s)"))
    println()

    // Process each classfile
    val results = classFiles.map { classFile =>
      val relativePath = classFile.relativeTo(targetDir)
      processClassFile(classFile, relativePath)
    }

    // Print summary
    println()
    println(fansi.Bold.On("=" * 80))
    printSummary(results)

    // Exit with appropriate code
    val summary = computeSummary(results)
    if (!summary.successful) {
      sys.exit(1)
    }
  }

  /** Finds all .class files in a directory recursively */
  private def findClassFiles(dir: os.Path): Seq[os.Path] = {
    os.walk(dir)
      .filter(os.isFile)
      .filter(_.ext == "class")
      .toSeq
  }

  /** Processes a single classfile */
  private def processClassFile(classFile: os.Path, relativePath: os.RelPath): (os.RelPath, PatchFileResult) = {
    print(fansi.Color.Cyan(s"Processing: $relativePath ... "))

    Try {
      // Read classfile
      val bytes = os.read.bytes(classFile)

      // Parse to detect version and lazy vals
      val classInfo = ClassfileParser.parse(bytes) match {
        case Right(info) => info
        case Left(error) => throw new RuntimeException(s"Failed to parse classfile: $error")
      }

      val detectionResult = LazyValDetector.detect(classInfo)

      detectionResult match {
        case LazyValDetectionResult.NoLazyVals =>
          // No lazy vals, skip
          PatchFileResult.Skipped("no lazy vals")

        case LazyValDetectionResult.LazyValsFound(lazyVals, version) =>
          version match {
            case ScalaVersion.Scala30x_31x =>
              // Fail fast: unsupported version
              throw new RuntimeException(
                s"Unsupported Scala version: 3.0-3.1 lazy vals detected (not yet implemented)"
              )

            case ScalaVersion.Scala32x =>
              // Fail fast: unsupported version
              throw new RuntimeException(
                s"Unsupported Scala version: 3.2 lazy vals detected (not yet implemented)"
              )

            case ScalaVersion.Scala33x_37x =>
              // Patchable version
              BytecodePatcher.patch(bytes) match {
                case BytecodePatcher.PatchResult.Patched(patchedBytes) =>
                  // Write back in place
                  os.write.over(classFile, patchedBytes)
                  PatchFileResult.Patched

                case BytecodePatcher.PatchResult.NotApplicable =>
                  PatchFileResult.NotApplicable

                case BytecodePatcher.PatchResult.Failed(error) =>
                  throw new RuntimeException(s"Patching failed: $error")
              }

            case ScalaVersion.Scala38Plus =>
              // Already 3.8+, no patching needed
              PatchFileResult.NotApplicable

            case ScalaVersion.Unknown =>
              // Fail fast: unknown version
              throw new RuntimeException("Unknown Scala version detected")
          }

        case LazyValDetectionResult.MixedVersions(versions) =>
          // Fail fast: mixed versions
          throw new RuntimeException(s"Mixed Scala versions detected: ${versions.mkString(", ")}")
      }
    } match {
      case Success(result) =>
        result match {
          case PatchFileResult.Patched =>
            println(fansi.Color.Green("✓ PATCHED"))
          case PatchFileResult.NotApplicable =>
            println(fansi.Color.Blue("○ NOT APPLICABLE"))
          case PatchFileResult.Skipped(reason) =>
            println(fansi.Color.LightBlue(s"- SKIPPED ($reason)"))
          case PatchFileResult.Failed(error) =>
            println(fansi.Color.Red(s"✗ FAILED: $error"))
        }
        (relativePath, result)

      case Failure(exception) =>
        println(fansi.Color.Red(s"✗ FAILED: ${exception.getMessage}"))
        Console.err.println()
        Console.err.println(fansi.Color.Red("Error details:"))
        exception.printStackTrace(Console.err)
        Console.err.println()
        sys.exit(1)
    }
  }

  /** Computes summary statistics */
  private def computeSummary(results: Seq[(os.RelPath, PatchFileResult)]): PatchSummary = {
    val total = results.size
    val patched = results.count(_._2 == PatchFileResult.Patched)
    val notApplicable = results.count(_._2 == PatchFileResult.NotApplicable)
    val skipped = results.count {
      case (_, PatchFileResult.Skipped(_)) => true
      case _                               => false
    }
    val failed = results.count {
      case (_, PatchFileResult.Failed(_)) => true
      case _                              => false
    }

    PatchSummary(total, patched, notApplicable, failed, skipped)
  }

  /** Prints summary of operations */
  private def printSummary(results: Seq[(os.RelPath, PatchFileResult)]): Unit = {
    val summary = computeSummary(results)

    println(fansi.Bold.On("Summary:"))
    println(s"  Total files processed: ${summary.total}")
    println(fansi.Color.Green(s"  Patched: ${summary.patched}"))
    println(fansi.Color.Blue(s"  Not applicable: ${summary.notApplicable}"))
    println(fansi.Color.LightBlue(s"  Skipped: ${summary.skipped}"))

    if (summary.failed > 0) {
      println(fansi.Color.Red(s"  Failed: ${summary.failed}"))
      println()
      println(fansi.Color.Red("Some files failed to patch!"))
    } else {
      println()
      println(fansi.Color.Green("✓ All files processed successfully!"))
    }
  }
}
