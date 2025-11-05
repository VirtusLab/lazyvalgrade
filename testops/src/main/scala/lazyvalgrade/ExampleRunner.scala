package lazyvalgrade

import java.security.MessageDigest
import scala.util.{Try, Success, Failure}
import scala.concurrent.{Future, ExecutionContext, blocking, Await, duration}
import ExecutionContext.Implicits.global
import duration._

/** Runs Scala compilation examples across multiple versions
  *
  * @param examplesRoot
  *   Root directory containing example subdirectories
  * @param workspaceRoot
  *   Root directory for compilation output
  * @param quiet
  *   If true, suppress all logging except errors and only show scala-cli output on failure
  */
class ExampleRunner(
    examplesRoot: os.Path,
    workspaceRoot: os.Path,
    quiet: Boolean = false
) {
  import scribe._

  // Set scribe logging level based on quiet mode
  if (quiet) {
    scribe.Logger.root
      .clearHandlers()
      .clearModifiers()
      .withHandler(minimumLevel = Some(Level.Error))
      .replace()
  }

  /** Validates that a directory exists and is a directory */
  private def validateDirectory(path: os.Path, name: String): Either[String, os.Path] =
    if !os.exists(path) then Left(s"$name does not exist: $path")
    else if !os.isDir(path) then Left(s"$name is not a directory: $path")
    else Right(path)

  /** Validates that a directory contains Scala source files */
  private def validateScalaSource(dir: os.Path): Either[String, os.Path] =
    val hasScalaFiles = os
      .list(dir)
      .exists(p => os.isFile(p) && p.toString.endsWith(".scala"))

    if hasScalaFiles then Right(dir)
    else Left(s"Directory contains no .scala files: $dir")

  /** Computes SHA-256 hash of a byte array */
  private def sha256(bytes: Array[Byte]): String = {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.digest(bytes).map("%02x".format(_)).mkString
  }

  /** Compiles an example directory with a specific Scala version
    *
    * @param sourceDir
    *   Directory containing Scala source files
    * @param scalaVersion
    *   Scala version to compile with
    * @param exampleWorkspace
    *   Workspace directory for this example
    * @return
    *   Try containing the compilation result
    */
  private def compileWithScalaCli(
      sourceDir: os.Path,
      scalaVersion: String,
      exampleWorkspace: os.Path
  ): Try[VersionCompilationResult] = Try {
    val targetDir = exampleWorkspace / scalaVersion
    os.makeDir.all(targetDir)

    // Copy sources to target directory
    os.walk(sourceDir)
      .filter(os.isFile)
      .foreach { source =>
        val relative = source.relativeTo(sourceDir)
        val target = targetDir / relative
        os.makeDir.all(target / os.up)
        os.copy.over(source, target)
      }

    if (!quiet) {
      info(s"Compiling with Scala $scalaVersion...")
    }

    // Run scala-cli compile with JDK 17 (for compatibility with older Scala versions)
    val result = os
      .proc("scala-cli", "compile", "--jvm", "17", "-S", scalaVersion, targetDir.toString)
      .call(cwd = targetDir, stderr = os.Pipe, stdout = os.Pipe, check = false)

    // Only log output if compilation failed or not in quiet mode
    if (result.exitCode != 0) {
      val stdout = result.out.text()
      val stderr = result.err.text()
      error(s"Compilation failed for Scala $scalaVersion (exit code ${result.exitCode})")
      if (stdout.nonEmpty) {
        error(s"stdout:\n$stdout")
      }
      if (stderr.nonEmpty) {
        error(s"stderr:\n$stderr")
      }
      throw new Exception(s"Compilation failed with exit code ${result.exitCode}")
    } else if (!quiet) {
      val output = result.out.text()
      debug(s"Compilation output for $scalaVersion: $output")
    }

    // Collect class files
    val classFiles = collectClassFiles(targetDir)

    VersionCompilationResult(
      scalaVersion = scalaVersion,
      success = true,
      classFiles = classFiles
    )
  }.recoverWith { case e: Exception =>
    error(s"Compilation failed for Scala $scalaVersion: ${e.getMessage}")
    Success(
      VersionCompilationResult(
        scalaVersion = scalaVersion,
        success = false,
        classFiles = Set.empty,
        error = Some(e.getMessage)
      )
    )
  }

  /** Collects class files from a compiled directory
    *
    * @param compiledDir
    *   Directory containing .scala-build output
    * @return
    *   Set of ClassFileInfo for all found class files
    */
  private def collectClassFiles(compiledDir: os.Path): Set[ClassFileInfo] = {
    val scalaBuildDir = compiledDir / ".scala-build"

    if !os.exists(scalaBuildDir) then
      if (!quiet) {
        warn(s".scala-build directory not found in $compiledDir")
      }
      return Set.empty

    // Find all .class files, skip .bloop directories
    val classFiles = os
      .walk(scalaBuildDir)
      .filter(p => os.isFile(p) && p.toString.endsWith(".class"))
      .filter(p => !p.toString.contains(".bloop"))
      .filter(p => !p.last.contains("$package"))

    classFiles.flatMap { classFile =>
      Try {
        val bytes = os.read.bytes(classFile)
        val fullPath = classFile.relativeTo(scalaBuildDir).toString

        // Normalize path: extract only the part after classes/main/ or classes/test/
        val normalizedPath = fullPath.split("/").toSeq match {
          case parts if parts.contains("classes") =>
            val classesIdx = parts.indexOf("classes")
            if classesIdx + 2 < parts.size then Some(parts.drop(classesIdx + 2).mkString("/"))
            else None
          case _ => None
        }

        normalizedPath.map { path =>
          ClassFileInfo(
            relativePath = path,
            absolutePath = classFile,
            size = bytes.length,
            sha256 = sha256(bytes)
          )
        }
      }.toOption.flatten
    }.toSet
  }

  /** Runs compilation for an example across multiple Scala versions
    *
    * @param examplePath
    *   Path to the example directory (relative to examplesRoot or absolute)
    * @param scalaVersions
    *   Set of Scala versions to compile with
    * @return
    *   Either error message or compilation results
    */
  def compileExample(
      examplePath: os.Path,
      scalaVersions: Set[String]
  ): Either[String, ExampleCompilationResult] = {
    // Resolve example path (handle both absolute and relative)
    val resolvedExample =
      if examplePath.startsWith(os.root) then examplePath
      else examplesRoot / os.RelPath(examplePath.toString)

    if (!quiet) {
      info(s"Running example: ${resolvedExample.last}")
    }

    // Clean desk protocol: nuke all .scala-build and .bsp directories from source
    // BEFORE any parallel compilation starts to avoid race conditions
    Seq(".scala-build", ".bsp").foreach { dirName =>
      val buildDir = resolvedExample / dirName
      if (os.exists(buildDir)) {
        if (!quiet) {
          debug(s"Cleaning $dirName from ${resolvedExample.last}...")
        }
        os.remove.all(buildDir)
      }
    }

    for {
      _ <- validateDirectory(resolvedExample, "Example directory")
      _ <- validateScalaSource(resolvedExample)
      _ <- validateDirectory(workspaceRoot, "Workspace directory")
    } yield {
      val exampleName = resolvedExample.last
      val exampleWorkspace = workspaceRoot / exampleName
      os.makeDir.all(exampleWorkspace)

      val results = Future
        .sequence(scalaVersions.toSeq.map { version =>
          Future {
            blocking {
              val result = compileWithScalaCli(resolvedExample, version, exampleWorkspace)
              if (!quiet) {
                result match {
                  case Success(r) if r.success =>
                    info(
                      s"  ✓ Scala $version compiled successfully (${r.classFiles.size} class files)"
                    )
                  case Success(r) =>
                    warn(s"  ✗ Scala $version compilation failed")
                  case Failure(e) =>
                    error(s"  ✗ Scala $version failed with exception: ${e.getMessage}")
                }
              }
              version -> result.get
            }
          }
        })
        .map(_.toMap)

      ExampleCompilationResult(
        exampleName = exampleName,
        results = Await.result(results, 3.minutes) // TODO: Make this configurable
      )
    }
  }

  /** Runs compilation for multiple examples
    *
    * @param examplePaths
    *   Paths to example directories
    * @param scalaVersions
    *   Set of Scala versions to compile with
    * @return
    *   Sequence of results or errors
    */
  def compileExamples(
      examplePaths: Seq[os.Path],
      scalaVersions: Set[String]
  ): Seq[Either[String, ExampleCompilationResult]] =
    examplePaths.map(compileExample(_, scalaVersions))

  /** Discovers all example directories in the examples root
    *
    * @return
    *   Either error message or sequence of example paths
    */
  def discoverExamples(): Either[String, Seq[os.Path]] =
    validateDirectory(examplesRoot, "Examples root").map { root =>
      os.list(root)
        .filter(os.isDir)
        .filter { dir =>
          os.list(dir)
            .exists(p => os.isFile(p) && p.toString.endsWith(".scala"))
        }
        .toSeq
    }
}

object ExampleRunner {

  /** Creates an ExampleRunner with os.Path parameters */
  def apply(examplesRoot: os.Path, workspaceRoot: os.Path, quiet: Boolean = false): ExampleRunner =
    new ExampleRunner(examplesRoot, workspaceRoot, quiet)

  /** Creates an ExampleRunner with String paths */
  def apply(examplesRoot: String, workspaceRoot: String, quiet: Boolean): ExampleRunner =
    new ExampleRunner(os.Path(examplesRoot), os.Path(workspaceRoot), quiet)
}
