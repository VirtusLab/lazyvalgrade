lazy val core = project
  .in(file("core"))
  .settings(
    name := "lazyvalgrade-core",
    version := "0.1.0-SNAPSHOT",
    scalaVersion := "3.8.1",
    libraryDependencies ++= Seq(
      "org.ow2.asm" % "asm" % "9.7",
      "org.ow2.asm" % "asm-commons" % "9.7",
      "org.ow2.asm" % "asm-util" % "9.7",
      "org.ow2.asm" % "asm-tree" % "9.7",
      "com.outr" %% "scribe" % "3.15.0",
      "com.lihaoyi" %% "os-lib" % "0.11.3"
    )
  )

lazy val testops = project
  .in(file("testops"))
  .settings(
    name := "lazyvalgrade-testops",
    version := "0.1.0-SNAPSHOT",
    scalaVersion := "3.8.1",
    libraryDependencies ++= Seq(
      "com.outr" %% "scribe" % "3.15.0",
      "com.lihaoyi" %% "os-lib" % "0.11.3",
      "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-core" % "2.32.0",
      "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-macros" % "2.32.0" % Provided
    ),
    Compile / mainClass := Some("lazyvalgrade.CompileExamplesMain"),
    assembly / mainClass := Some("lazyvalgrade.CompileExamplesMain"),
    assembly / assemblyJarName := "lazyvalgrade-testops.jar",
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", xs @ _*) => MergeStrategy.discard
      case x => MergeStrategy.first
    }
  )
  .dependsOn(core)

lazy val tests = project
  .in(file("tests"))
  .settings(
    name := "lazyvalgrade-tests",
    version := "0.1.0-SNAPSHOT",
    scalaVersion := "3.8.1",
    libraryDependencies ++= Seq(
      "org.scalameta" %% "munit" % "1.0.0" % Test,
      "com.outr" %% "scribe" % "3.15.0",
      "com.lihaoyi" %% "os-lib" % "0.11.3"
    ),
    testFrameworks += new TestFramework("munit.Framework"),
    Test / test := ((Test / test) dependsOn (agent / assembly)).value,
    Test / testOnly := ((Test / testOnly) dependsOn (agent / assembly)).evaluated
  )
  .dependsOn(core, testops, agent)

lazy val cli = project
  .in(file("cli"))
  .settings(
    name := "lazyvalgrade-cli",
    version := "0.1.0-SNAPSHOT",
    scalaVersion := "3.8.1",
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "os-lib" % "0.11.3",
      "com.lihaoyi" %% "fansi" % "0.5.0",
      "com.outr" %% "scribe" % "3.15.0"
    ),
    Compile / mainClass := Some("lazyvalgrade.cli.Main"),
    assembly / mainClass := Some("lazyvalgrade.cli.Main"),
    assembly / assemblyJarName := "lazyvalgrade.jar",
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", xs @ _*) => MergeStrategy.discard
      case x => MergeStrategy.first
    }
  )
  .dependsOn(core)

lazy val processDeps = taskKey[Classpath]("Process agent dependency JARs to patch lazy vals")

lazy val agent = project
  .in(file("agent"))
  .settings(
    name := "lazyvalgrade-agent",
    version := "0.1.0-SNAPSHOT",
    scalaVersion := "3.8.1",
    libraryDependencies ++= Seq(
      "com.outr" %% "scribe" % "3.15.0"
    ),
    processDeps := {
      val log = streams.value.log
      val cliJar = (cli / assembly).value
      val deps = (Compile / dependencyClasspath).value
      val processedDir = target.value / "processed-deps"
      IO.createDirectory(processedDir)

      val depJars = deps.files.filter(_.getName.endsWith(".jar"))
      // Build full classpath: CLI jar + all dependency JARs so ASM can resolve class hierarchies
      val fullCp = (cliJar +: depJars).map(_.getAbsolutePath).mkString(java.io.File.pathSeparator)

      val debugAssembly = sys.env.contains("DEBUG_AGENT_ASSEMBLY")
      val processLogger: scala.sys.process.ProcessLogger = if (debugAssembly)
        scala.sys.process.ProcessLogger(s => log.info(s), s => log.error(s))
      else scala.sys.process.ProcessLogger(_ => (), _ => ())

      val processedFiles = depJars.map { depJar =>
        val dest = processedDir / depJar.getName
        IO.copyFile(depJar, dest)
        if (debugAssembly) log.info(s"Processing ${depJar.getName}...")
        val exitCode = scala.sys.process.Process(
          Seq("java", "-cp", fullCp, "lazyvalgrade.cli.Main", dest.getAbsolutePath)
        ).!(processLogger)
        if (exitCode != 0) {
          throw new MessageOnlyException(s"Failed to process ${depJar.getName} (exit code $exitCode)")
        }
        Attributed.blank(dest)
      }

      processedFiles
    },
    assembly / fullClasspath := {
      val processed = processDeps.value
      val deps = (Compile / dependencyClasspath).value
      val ownProducts = (Compile / products).value.map(Attributed.blank)
      // Keep non-JAR entries (class directories from project dependencies like core)
      val nonJarDeps = deps.filterNot(_.data.getName.endsWith(".jar"))
      ownProducts ++ nonJarDeps ++ processed
    },
    assembly / assemblyJarName := "lazyvalgrade-agent.jar",
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", "MANIFEST.MF") => MergeStrategy.discard
      case PathList("META-INF", xs @ _*) => MergeStrategy.discard
      case x => MergeStrategy.first
    },
    assembly / assemblyShadeRules := Seq(
      ShadeRule.rename("lazyvalgrade.**" -> "lazyvalgrade.shaded.agent.@0").inAll,
      ShadeRule.rename("scala.**" -> "lazyvalgrade.shaded.scala.@1").inAll,
      ShadeRule.rename("org.objectweb.asm.**" -> "lazyvalgrade.shaded.asm.@1").inAll,
      ShadeRule.rename("scribe.**" -> "lazyvalgrade.shaded.scribe.@1").inAll,
      ShadeRule.rename("perfolation.**" -> "lazyvalgrade.shaded.perfolation.@1").inAll,
      ShadeRule.rename("moduload.**" -> "lazyvalgrade.shaded.moduload.@1").inAll,
      ShadeRule.rename("sourcecode.**" -> "lazyvalgrade.shaded.sourcecode.@1").inAll,
      ShadeRule.rename("com.lihaoyi.**" -> "lazyvalgrade.shaded.lihaoyi.@1").inAll,
      ShadeRule.rename("os.**" -> "lazyvalgrade.shaded.os.@1").inAll,
      ShadeRule.rename("geny.**" -> "lazyvalgrade.shaded.geny.@1").inAll
    ),
    assembly / packageOptions += Package.ManifestAttributes(
      "Premain-Class" -> "lazyvalgrade.shaded.agent.lazyvalgrade.agent.LazyValGradeAgent",
      "Can-Retransform-Classes" -> "false",
      "Can-Redefine-Classes" -> "false"
    )
  )
  .dependsOn(core)

lazy val agentInstall = taskKey[Unit]("Build agent assembly and install to ~/.lazyvalgrade/agent.jar")

lazy val root = project
  .in(file("."))
  .settings(
    name := "lazyvalgrade",
    scalaVersion := "3.8.1",
    agentInstall := {
      val assembled = (agent / assembly).value
      val target = Path.userHome / ".lazyvalgrade" / "agent.jar"
      IO.createDirectory(target.getParentFile)
      IO.copyFile(assembled, target)
      streams.value.log.info(s"Installed agent to $target")
    },
    addCommandAlias("compileExamples", "testops/runMain lazyvalgrade.CompileExamplesMain"),
    addCommandAlias("compileExamplesWithPatching", "testops/runMain lazyvalgrade.CompileExamplesMain --patch")
  )
  .aggregate(core, testops, tests, cli, agent)
