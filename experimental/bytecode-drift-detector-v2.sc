//> using scala 3.7.3
//> using dep com.lihaoyi::os-lib:0.11.3
//> using dep com.outr::scribe:3.15.0
//> using file ../testops/src/main/scala/lazyvalgrade/CompilationResult.scala
//> using file ../testops/src/main/scala/lazyvalgrade/ExampleRunner.scala

import lazyvalgrade._

def main(examplesDir: String, scalaVersions: String*): Unit = {
  if scalaVersions.isEmpty then
    println("Error: No Scala versions provided")
    println(
      "Usage: scala-cli run bytecode-drift-detector-v2.sc -- <examples-dir> <version1> <version2> ..."
    )
    sys.exit(1)

  val cwd = os.pwd
  val examplesPath = cwd / examplesDir
  val workspacePath = cwd / "workspace"

  val runner = ExampleRunner(examplesPath, workspacePath)

  println("=" * 80)
  println("Bytecode Drift Detector v2")
  println("=" * 80)
  println(s"Examples directory: $examplesPath")
  println(s"Scala versions to test: ${scalaVersions.mkString(", ")}")
  println(s"Workspace: $workspacePath")

  runner.discoverExamples() match {
    case Left(error) =>
      println(s"\nError: $error")
      sys.exit(1)

    case Right(examples) =>
      println(s"\nFound ${examples.size} example(s)")

      examples.foreach { examplePath =>
        runner.runExample(examplePath, scalaVersions.toSet) match {
          case Left(error) =>
            println(s"\n❌ Error processing ${examplePath.last}: $error")

          case Right(result) =>
            printResult(result)
        }
      }
  }

  println("\n" + "=" * 80)
  println("Analysis complete")
  println("=" * 80)
}

def printResult(result: ExampleCompilationResult): Unit = {
  println(s"\n📦 Example: ${result.exampleName}")
  println(
    s"  Comparing across ${result.successfulResults.size} Scala versions: ${result.successfulResults.keys.toSeq.sorted.mkString(", ")}"
  )

  val allClasses = result.allClassPaths.toSeq.sorted

  if allClasses.isEmpty then
    println("  ⚠️  No class files found")
    return

  println(s"\n  Found ${allClasses.size} unique class files")

  var driftCount = 0

  allClasses.foreach { classPath =>
    val filesForPath = result.classFilesByPath(classPath)

    if filesForPath.size != result.successfulResults.size then
      println(
        s"\n  ⚠️  $classPath - Only present in: ${filesForPath.keys.mkString(", ")}"
      )
      driftCount += 1
    else if !result.isIdentical(classPath) then
      println(s"\n  🔴 DRIFT DETECTED: $classPath")
      filesForPath.toSeq.sortBy(_._1).foreach { case (version, info) =>
        println(
          f"    Scala $version%-12s - Size: ${info.size}%6d bytes, SHA256: ${info.sha256.take(16)}..."
        )
      }
      driftCount += 1
  }

  if driftCount == 0 then
    println(
      "\n  ✅ No bytecode drift detected - all versions produce identical bytecode"
    )
}

main(args(0), args.drop(1)*)
