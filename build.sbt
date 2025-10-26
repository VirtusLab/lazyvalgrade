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
      "com.lihaoyi" %% "os-lib" % "0.11.3"
    )
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

lazy val root = project
  .in(file("."))
  .settings(name := "lazyvalgrade")
  .aggregate(core, testops, tests)
