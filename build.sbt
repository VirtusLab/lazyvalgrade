lazy val core = project
  .in(file("core"))
  .settings(
    name := "lazyvalgrade-core",
    version := "0.1.0-SNAPSHOT",
    scalaVersion := "3.7.3",
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
    scalaVersion := "3.7.3",
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
    scalaVersion := "3.7.3",
    libraryDependencies ++= Seq(
      "org.scalameta" %% "munit" % "1.0.0" % Test,
      "com.outr" %% "scribe" % "3.15.0",
      "com.lihaoyi" %% "os-lib" % "0.11.3"
    ),
    testFrameworks += new TestFramework("munit.Framework")
  )
  .dependsOn(core, testops)

lazy val cli = project
  .in(file("cli"))
  .settings(
    name := "lazyvalgrade-cli",
    version := "0.1.0-SNAPSHOT",
    scalaVersion := "3.7.3",
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

lazy val agent = project
  .in(file("agent"))
  .settings(
    name := "lazyvalgrade-agent",
    version := "0.1.0-SNAPSHOT",
    scalaVersion := "3.7.3",
    assembly / assemblyJarName := "lazyvalgrade-agent.jar",
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", "MANIFEST.MF") => MergeStrategy.discard
      case PathList("META-INF", xs @ _*) => MergeStrategy.discard
      case x => MergeStrategy.first
    },
    assembly / assemblyShadeRules := Seq(
      ShadeRule.rename("scala.**" -> "lazyvalgrade.shaded.scala.@1").inAll,
      ShadeRule.rename("org.objectweb.asm.**" -> "lazyvalgrade.shaded.asm.@1").inAll,
      ShadeRule.rename("scribe.**" -> "lazyvalgrade.shaded.scribe.@1").inAll,
      ShadeRule.rename("perfolation.**" -> "lazyvalgrade.shaded.perfolation.@1").inAll,
      ShadeRule.rename("moduload.**" -> "lazyvalgrade.shaded.moduload.@1").inAll,
      ShadeRule.rename("com.lihaoyi.**" -> "lazyvalgrade.shaded.lihaoyi.@1").inAll
    ),
    assembly / packageOptions += Package.ManifestAttributes(
      "Premain-Class" -> "lazyvalgrade.agent.LazyValGradeAgent",
      "Can-Retransform-Classes" -> "false",
      "Can-Redefine-Classes" -> "false"
    )
  )
  .dependsOn(core)

lazy val root = project
  .in(file("."))
  .settings(
    name := "lazyvalgrade",
    scalaVersion := "3.7.3",
    addCommandAlias("compileExamples", "testops/runMain lazyvalgrade.CompileExamplesMain"),
    addCommandAlias("compileExamplesWithPatching", "testops/runMain lazyvalgrade.CompileExamplesMain --patch")
  )
  .aggregate(core, testops, tests, cli, agent)
