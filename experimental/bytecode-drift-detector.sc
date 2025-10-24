//> using scala 3.7.3
//> using dep com.lihaoyi::os-lib:0.11.3

import java.security.MessageDigest
import scala.sys.process._
import scala.util.{Try, Success, Failure}

case class ClassFileInfo(
    relativePath: String,
    size: Long,
    sha256: String
)

case class CompilationResult(
    scalaVersion: String,
    classFiles: Map[String, ClassFileInfo]
)

def sha256(bytes: Array[Byte]): String =
  val digest = MessageDigest.getInstance("SHA-256")
  digest.digest(bytes).map("%02x".format(_)).mkString

def compileWithScalaCli(
    sourceDir: os.Path,
    scalaVersion: String,
    workspaceDir: os.Path
): Try[os.Path] = Try:
  val targetDir = workspaceDir / scalaVersion
  os.makeDir.all(targetDir)

  // Copy sources to target directory
  os.copy.over(
    sourceDir,
    targetDir,
    replaceExisting = true
  )

  println(s"  Compiling with Scala $scalaVersion...")

  // Run scala-cli compile
  val command =
    Seq("scala-cli", "compile", "-S", scalaVersion, targetDir.toString)
  val result = command.!!

  targetDir

def collectClassFiles(compiledDir: os.Path): Map[String, ClassFileInfo] =
  val scalaBuildDir = compiledDir / ".scala-build"

  if !os.exists(scalaBuildDir) then
    println(s"    Warning: .scala-build directory not found in ${compiledDir}")
    return Map.empty

  // Find all .class files recursively, skip .bloop directories
  val classFiles = os
    .walk(scalaBuildDir)
    .filter(_.ext == "class")
    .filter(!_.segments.contains(".bloop")) // Skip .bloop directories
    .filter(!_.last.contains("$package")) // Skip package objects for now

  classFiles.flatMap: classFile =>
    val bytes = os.read.bytes(classFile)
    val fullPath = classFile.relativeTo(scalaBuildDir).toString

    // Normalize path: extract only the part after classes/main/ or classes/test/
    val normalizedPath = fullPath.split("/").toSeq match
      case parts if parts.contains("classes") =>
        val classesIdx = parts.indexOf("classes")
        if classesIdx + 2 < parts.size then
          // Skip project hash and keep from classes/main/ onwards
          Some(parts.drop(classesIdx + 2).mkString("/"))
        else None
      case _ => None

    normalizedPath.map: path =>
      val info = ClassFileInfo(
        relativePath = path,
        size = bytes.length,
        sha256 = sha256(bytes)
      )
      (path, info)
  .toMap

def compareClassFiles(results: Seq[CompilationResult]): Unit =
  if results.isEmpty then
    println("  No compilation results to compare")
    return

  // Get all unique class file paths across all versions
  val allClassFiles = results.flatMap(_.classFiles.keys).distinct.sorted

  println(s"\n  Found ${allClassFiles.size} unique class files")
  println(
    s"  Comparing across ${results.size} Scala versions: ${results.map(_.scalaVersion).mkString(", ")}"
  )

  var driftDetected = false

  allClassFiles.foreach: classPath =>
    val infos = results.flatMap: result =>
      result.classFiles.get(classPath).map(info => (result.scalaVersion, info))

    if infos.isEmpty then
      println(s"\n  ⚠️  $classPath - Not found in any version")
    else if infos.size != results.size then
      println(
        s"\n  ⚠️  $classPath - Only present in: ${infos.map(_._1).mkString(", ")}"
      )
      driftDetected = true
    else
      // Check if all versions produce identical bytecode
      val uniqueShas = infos.map(_._2.sha256).distinct
      val uniqueSizes = infos.map(_._2.size).distinct

      if uniqueShas.size > 1 || uniqueSizes.size > 1 then
        println(s"\n  🔴 DRIFT DETECTED: $classPath")
        infos.foreach: 
          case (version, info) =>
            println(
              f"    Scala $version%-8s - Size: ${info.size}%6d bytes, SHA256: ${info.sha256.take(16)}..."
            )
        driftDetected = true

  if !driftDetected then
    println(
      "\n  ✅ No bytecode drift detected - all versions produce identical bytecode"
    )

def processExample(
    exampleDir: os.Path,
    scalaVersions: Seq[String],
    workspaceRoot: os.Path
): Unit =
  val exampleName = exampleDir.last
  println(s"\n📦 Processing example: $exampleName")

  val workspaceDir = workspaceRoot / exampleName
  os.makeDir.all(workspaceDir)

  val results = scalaVersions.flatMap: version =>
    compileWithScalaCli(exampleDir, version, workspaceDir) match
      case Success(compiledDir) =>
        val classFiles = collectClassFiles(compiledDir)
        println(
          s"    ✓ Scala $version compiled successfully (${classFiles.size} class files)"
        )
        Some(CompilationResult(version, classFiles))
      case Failure(exception) =>
        println(
          s"    ✗ Scala $version compilation failed: ${exception.getMessage}"
        )
        None

  if results.nonEmpty then
    compareClassFiles(results)

def main(examplesDir: String, scalaVersions: String*): Unit =
  val examplesDirPath = os.Path(examplesDir, os.pwd)

  if !os.exists(examplesDirPath) then
    println(s"Error: Examples directory not found: $examplesDirPath")
    sys.exit(1)

  if scalaVersions.isEmpty then
    println("Error: No Scala versions provided")
    println(
      "Usage: scala-cli run bytecode-drift-detector.sc -- <examples-dir> <version1> <version2> ..."
    )
    sys.exit(1)

  println("=" * 80)
  println("Bytecode Drift Detector")
  println("=" * 80)
  println(s"Examples directory: $examplesDirPath")
  println(s"Scala versions to test: ${scalaVersions.mkString(", ")}")
  println(s"Workspace: ${os.pwd / "workspace"}")

  val workspaceRoot = os.pwd / "workspace"
  os.makeDir.all(workspaceRoot)

  // Find all example directories
  val examples = os
    .list(examplesDirPath)
    .filter(os.isDir)
    .filter(dir => os.list(dir).exists(_.ext == "scala"))

  if examples.isEmpty then
    println(
      s"\nNo example directories with .scala files found in $examplesDirPath"
    )
    sys.exit(1)

  println(s"\nFound ${examples.size} example(s)")

  examples.foreach: exampleDir =>
    processExample(exampleDir, scalaVersions, workspaceRoot)

  println("\n" + "=" * 80)
  println("Analysis complete")
  println("=" * 80)

main(args(0), args.drop(1)*)