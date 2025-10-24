name := "lazyvalgrade"

version := "0.1.0-SNAPSHOT"

scalaVersion := "3.8.0"

lazy val core = project
  .in(file("core"))
  .settings(
    name := "lazyvalgrade-core",
    libraryDependencies ++= Seq(
      "org.ow2.asm" % "asm" % "9.7",
      "org.ow2.asm" % "asm-commons" % "9.7",
      "org.ow2.asm" % "asm-util" % "9.7"
    )
  )
