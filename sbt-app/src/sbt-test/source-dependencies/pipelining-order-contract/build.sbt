/*
 * sbt
 * Copyright 2026, Scala Center, and the sbt contributors.
 * Licensed under Apache License 2.0 (see LICENSE).
 */

Global / localCacheDirectory := (ThisBuild / baseDirectory).value / "contract-cache"

import sbt.complete.DefaultParsers.*

ThisBuild / scalaVersion := sys.props.getOrElse("test.scala.version", "2.13.18")
ThisBuild / usePipelining := true
Global / concurrentRestrictions := Seq(Tags.limitAll(32))

lazy val arm = inputKey[Unit]("Hold an upstream compiler until a downstream API is ready")
lazy val checkContract = inputKey[Unit]("Check recorded compiler order and overlap")
lazy val checkJavaOnly = inputKey[Unit]("Check one actual javac invocation for a pure Java project")
lazy val checkedCompile = taskKey[Unit]("Compile and expose any underlying task failure")
lazy val resetTrace = taskKey[Unit]("Clear compiler events")
lazy val checkNoCompile = taskKey[Unit]("Ensure no compiler ran")
lazy val checkJfScopes = taskKey[Unit]("Check Java-first configuration output isolation")
lazy val checkSfScopes = taskKey[Unit]("Check Scala-first configuration output isolation")
lazy val diagnose = taskKey[Unit]("Record loaded compiler settings and runtime")
lazy val cancelGate = taskKey[Unit]("Release any pending compiler gate")

checkJavaOnly / aggregate := false
checkJavaOnly := Contract.checkJavaOnly(spaceDelimited("project/configuration").parsed.head)
resetTrace / aggregate := false
checkNoCompile / aggregate := false
resetTrace := Contract.reset()
checkNoCompile := Contract.checkNoCompile()
arm / aggregate := false
checkContract / aggregate := false
cancelGate / aggregate := false
arm := {
  val args = spaceDelimited("upstream downstream").parsed
  Contract.arm(args(0), args(1))
}
checkContract := {
  val args = spaceDelimited("upstream downstream javaFirst").parsed
  Contract.check(args(0), args(1), args(2).toBoolean)
}
cancelGate := Contract.cancel()
diagnose / aggregate := false
diagnose := {
  val location = sbt.Defaults.getClass.getProtectionDomain.getCodeSource.getLocation
  val jar = new File(location.toURI)
  println(s"Defaults loaded from $location")
  if jar.isFile then println("Defaults SHA-256 " + java.security.MessageDigest.getInstance("SHA-256").digest(IO.readBytes(jar)).map(b => f"${b & 0xff}%02x").mkString)
  println("jfUp usePipelining=" + (jfUp / Compile / usePipelining).value)
  println("jfUp exportPipelining=" + (jfUp / Compile / exportPipelining).value)
}

def observed(id: String): Seq[Setting[?]] = Seq(
  fork := true,
  checkedCompile := Def.uncached {
    (Compile / compile).result.value match {
      case Result.Value(_) => ()
      case Result.Inc(error) =>
        def dump(current: Incomplete): Unit = {
          println("Failed task node: " + current.node)
          current.directCause.foreach(_.printStackTrace())
          current.causes.foreach(dump)
        }
        dump(error)
        throw error
    }
  },
  Compile / scalacOptions ~= (opts => { assert(opts.count(_ == "-Ypickle-write") == 1, opts.mkString(" ")); opts }),
  Test / scalacOptions ~= (opts => { assert(opts.count(_ == "-Ypickle-write") == 1, opts.mkString(" ")); opts }),
  Compile / incOptions ~= (o => { println(s"$id/compile effective pipelining=${o.pipelining()}"); assert(o.pipelining()); o }),
  Test / incOptions ~= (o => { println(s"$id/test effective pipelining=${o.pipelining()}"); assert(o.pipelining()); o }),
  Compile / compile / compileProgress ~= (p => Contract.progress(id + "/compile", p)),
  Test / compile / compileProgress ~= (p => Contract.progress(id + "/test", p)),
  Compile / compilers ~= (cs => Contract.compilers(id + "/compile", cs)),
  Test / compilers ~= (cs => Contract.compilers(id + "/test", cs)),
)

lazy val root = project.in(file(".")).aggregate(javaOnly, jfJavaOnly, sfJavaOnly, scalaOnly, jfUp, jfLeft, jfRight, jfDiamond, jfTail, sfUp, sfLeft, sfRight, sfDiamond, sfTail)
lazy val javaOnly = project.settings(observed("javaOnly"))
lazy val scalaOnly = project.settings(observed("scalaOnly"))

lazy val jfJavaOnly = project.dependsOn(scalaOnly).settings(observed("jfJavaOnly")).settings(compileOrder := CompileOrder.JavaThenScala)

lazy val jfUp = project.dependsOn(javaOnly, jfJavaOnly, scalaOnly).settings(observed("jfUp")).settings(compileOrder := CompileOrder.JavaThenScala)
lazy val jfLeft = project.dependsOn(jfUp % "compile->compile;test->test").settings(observed("jfLeft")).settings(compileOrder := CompileOrder.JavaThenScala)
lazy val jfRight = project.dependsOn(jfUp % "compile->compile;test->test").settings(observed("jfRight")).settings(compileOrder := CompileOrder.JavaThenScala)
lazy val jfDiamond = project.dependsOn(jfLeft % "compile->compile;test->test", jfRight % "compile->compile;test->test").settings(observed("jfDiamond")).settings(compileOrder := CompileOrder.JavaThenScala)
lazy val jfTail = project.dependsOn(jfDiamond % "compile->compile;test->test").settings(observed("jfTail")).settings(compileOrder := CompileOrder.JavaThenScala)

lazy val sfJavaOnly = project.dependsOn(scalaOnly).settings(observed("sfJavaOnly")).settings(compileOrder := CompileOrder.ScalaThenJava)

lazy val sfUp = project.dependsOn(javaOnly, sfJavaOnly, scalaOnly).settings(observed("sfUp")).settings(compileOrder := CompileOrder.ScalaThenJava)
lazy val sfLeft = project.dependsOn(sfUp % "compile->compile;test->test").settings(observed("sfLeft")).settings(compileOrder := CompileOrder.ScalaThenJava)
lazy val sfRight = project.dependsOn(sfUp % "compile->compile;test->test").settings(observed("sfRight")).settings(compileOrder := CompileOrder.ScalaThenJava)
lazy val sfDiamond = project.dependsOn(sfLeft % "compile->compile;test->test", sfRight % "compile->compile;test->test").settings(observed("sfDiamond")).settings(compileOrder := CompileOrder.ScalaThenJava)
lazy val sfTail = project.dependsOn(sfDiamond % "compile->compile;test->test").settings(observed("sfTail")).settings(compileOrder := CompileOrder.ScalaThenJava)

lazy val invalidJavaFirst = project.settings(compileOrder := CompileOrder.JavaThenScala)
lazy val invalidScalaFirst = project.settings(compileOrder := CompileOrder.ScalaThenJava)

checkJfScopes / aggregate := false
checkJfScopes := {
  val converter = fileConverter.value
  Contract.checkOutput(converter.toPath((jfUp / Compile / earlyOutput).value), "jf/")
  Contract.checkOutput(converter.toPath((jfUp / Test / earlyOutput).value), "jftest/")
}

checkSfScopes / aggregate := false
checkSfScopes := {
  val converter = fileConverter.value
  Contract.checkOutput(converter.toPath((sfUp / Compile / earlyOutput).value), "sf/")
  Contract.checkOutput(converter.toPath((sfUp / Test / earlyOutput).value), "sftest/")
}

lazy val hiddenApi = project
lazy val middleApi = project.dependsOn(hiddenApi)
lazy val directConsumer = project.dependsOn(middleApi).settings(
  compileOrder := CompileOrder.JavaThenScala,
  dependencyMode := DependencyMode.Direct,
)
lazy val plusOneConsumer = project.dependsOn(middleApi).settings(
  compileOrder := CompileOrder.JavaThenScala,
  dependencyMode := DependencyMode.PlusOne,
)
lazy val effectiveJavaFirst = project.dependsOn(scalaOnly).settings(
  compileOrder := CompileOrder.JavaThenScala,
  usePipelining := false,
  exportPipelining := true,
  incOptions ~= (_.withPipelining(true)),
)

lazy val deleteEarlyOutputs = taskKey[Unit]("Delete cached early artifacts")
lazy val checkEarlyRestored = taskKey[Unit]("Check restored early artifacts")
deleteEarlyOutputs / aggregate := false
checkEarlyRestored / aggregate := false
deleteEarlyOutputs := {
  val converter = fileConverter.value
  IO.delete(converter.toPath((jfUp / Compile / earlyOutput).value).toFile)
  IO.delete((jfUp / Compile / earlyCompileAnalysisFile).value)
}
checkEarlyRestored := {
  val converter = fileConverter.value
  Contract.checkOutput(converter.toPath((jfUp / Compile / earlyOutput).value), "jf/")
  assert((jfUp / Compile / earlyCompileAnalysisFile).value.exists)
}


lazy val crossScalaFirst = project.dependsOn(jfUp).settings(observed("crossScalaFirst")).settings(compileOrder := CompileOrder.ScalaThenJava)
lazy val crossJavaFirst = project.dependsOn(sfUp).settings(observed("crossJavaFirst")).settings(compileOrder := CompileOrder.JavaThenScala)
