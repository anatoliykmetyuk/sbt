/*
 * sbt
 * Copyright 2026, Scala Center, and the sbt contributors.
 * Licensed under Apache License 2.0 (see LICENSE).
 */

import java.util.Optional
import java.util.concurrent.{ ConcurrentLinkedQueue, CountDownLatch, TimeUnit }
import scala.jdk.CollectionConverters.*
import xsbti.*
import xsbti.compile.*

object Contract:
  private val events = new ConcurrentLinkedQueue[String]()
  @volatile private var held = ""
  @volatile private var release = ""
  @volatile private var latch = new CountDownLatch(0)

  def arm(upstream: String, downstream: String): Unit =
    events.clear()
    held = upstream
    release = downstream
    latch = new CountDownLatch(1)

  private def record(id: String, event: String): Unit =
    events.add(s"$id:$event")

  def progress(id: String, delegate: CompileProgress): CompileProgress = new CompileProgress:
    override def startUnit(phase: String, path: String): Unit =
      record(id, s"phase:$phase:$path")
      delegate.startUnit(phase, path)
    override def advance(current: Int, total: Int, previous: String, next: String): Boolean =
      delegate.advance(current, total, previous, next)
    override def afterEarlyOutput(success: Boolean): Unit =
      record(id, s"early:$success")
      delegate.afterEarlyOutput(success)
      if success && id == release then
        assert(!events.contains(s"$held:scalac:end"), s"$held already completed: ${events.asScala.mkString(", ")}")
        latch.countDown()
      if success && id == held then
        try
          assert(latch.await(45, TimeUnit.SECONDS), s"$release did not typecheck while $held was held: ${events.asScala.mkString(", ")}")
        finally latch.countDown()

  def compilers(id: String, cs: Compilers): Compilers =
    val originalScala = cs.scalac()
    val originalJava = cs.javaTools()
    val scala = new ScalaCompiler:
      override def scalaInstance(): ScalaInstance = originalScala.scalaInstance()
      override def classpathOptions(): ClasspathOptions = originalScala.classpathOptions()
      override def compile(
          sources: Array[VirtualFile], classpath: Array[VirtualFile], converter: FileConverter,
          changes: DependencyChanges, options: Array[String], output: Output,
          callback: AnalysisCallback, reporter: Reporter, progress: Optional[CompileProgress], log: Logger
      ): Unit =
        val phase = if sources.exists(_.name().endsWith(".scala")) then "scalac" else "java-api"
        record(id, phase + ":start:" + sources.map(_.name()).sorted.mkString(","))
        originalScala.compile(sources, classpath, converter, changes, options, output, callback, reporter, progress, log)
        record(id, phase + ":end")
    val java = new JavaCompiler:
      override def supportsDirectToJar(): Boolean = originalJava.javac().supportsDirectToJar()
      override def run(
          sources: Array[VirtualFile], options: Array[String], output: Output,
          toolOptions: IncToolOptions, reporter: Reporter, log: Logger
      ): Boolean =
        record(id, "javac:start")
        val success = originalJava.javac().run(sources, options, output, toolOptions, reporter, log)
        record(id, s"javac:end:$success")
        success
    cs.withScalac(scala).withJavaTools(new JavaTools:
      override def javac(): JavaCompiler = java
      override def javadoc(): Javadoc = originalJava.javadoc()
    )

  def check(upstream: String, downstream: String, javaFirst: Boolean): Unit =
    val xs = events.asScala.toVector
    def index(event: String): Int =
      val i = xs.indexWhere(_.startsWith(event))
      assert(i >= 0, s"Missing $event: ${xs.mkString(", ")}")
      i
    assert(index(s"$upstream:early:true") < index(s"$downstream:early:true"))
    assert(index(s"$downstream:early:true") < index(s"$upstream:scalac:end"))
    if javaFirst then
      assert(index(s"$upstream:javac:end:true") < index(s"$upstream:scalac:start:"))
    else
      assert(index(s"$upstream:scalac:end") < index(s"$upstream:javac:start"))
    assert(xs.count(_ == s"$upstream:javac:start") == 1, xs.mkString(", "))
    val mainScalaRuns = xs.filter(_.startsWith(s"$upstream:scalac:start:")).filter(_.contains(".scala"))
    assert(mainScalaRuns.forall(!_.contains(".java")), mainScalaRuns.mkString(", "))
    println(s"Verified order and typed downstream overlap: $upstream -> $downstream")
    cancel()

  def checkJavaOnly(id: String): Unit =
    val xs = events.asScala.toVector
    assert(xs.count(_ == s"$id:javac:start") == 1, xs.mkString(", "))

  def checkOutput(path: java.nio.file.Path, prefix: String): Unit =
    val jar = new java.util.zip.ZipFile(path.toFile)
    try
      val entries = jar.entries().asScala.map(_.getName).filter(n => n.endsWith(".sig") || n.endsWith(".tasty") || n.endsWith(".class")).toVector
      assert(entries.nonEmpty && entries.forall(_.startsWith(prefix)), s"$path: ${entries.mkString(", ")}")
      assert(entries.exists(_.startsWith(prefix + "UpJava.")), entries.mkString(", "))
      assert(entries.exists(_.startsWith(prefix + "UpScala.")), entries.mkString(", "))
    finally jar.close()

  def reset(): Unit =
    cancel()
    events.clear()

  def checkNoCompile(): Unit =
    val runs = events.asScala.filter(e => e.contains(":scalac:start:") || e.contains(":java-api:start:") || e.endsWith(":javac:start")).toVector
    assert(runs.isEmpty, runs.mkString(", "))

  def cancel(): Unit =
    latch.countDown()
    held = ""
    release = ""
end Contract
