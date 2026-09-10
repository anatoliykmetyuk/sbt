ThisBuild / scalaVersion := "2.13.18"
ThisBuild / crossScalaVersions := Seq("2.12.21", "2.13.18", "3.3.8")
ThisBuild / usePipelining := true
ThisBuild / compileOrder := CompileOrder.JavaThenScala

lazy val root = (project in file("."))
  .aggregate(javaFirst, scalaFirst, javaOnly, exportOnly, downstream)

lazy val javaFirst = project.settings(
  exportPipelining := true,
  Test / compileOrder := CompileOrder.JavaThenScala,
  Test / exportPipelining := true,
)

lazy val scalaFirst = project.settings(
  Compile / compileOrder := CompileOrder.ScalaThenJava,
  Compile / exportPipelining := true,
)

lazy val javaOnly = project

lazy val exportOnly = project.settings(
  compileOrder := CompileOrder.Mixed,
  usePipelining := false,
  exportPipelining := scalaVersion.value != "3.3.8",
)

lazy val downstream = project
  .dependsOn(javaFirst, scalaFirst, javaOnly, exportOnly)
  .settings(
    compileOrder := CompileOrder.Mixed,
    TaskKey[Unit]("checkPickle") := Def.uncached {
      val _ = (exportOnly / Compile / compile).value
      val picklePath = (Compile / internalDependencyPicklePath).value
      val early = (exportOnly / Compile / earlyOutput).value
      assert(picklePath.exists(_.data.id == early.id), s"picklePath = $picklePath")
      assert(picklePath.count(_.data.id.contains("early")) == 1, s"picklePath = $picklePath")
      assert(fileConverter.value.toPath(early).toFile.exists)
    },
  )
