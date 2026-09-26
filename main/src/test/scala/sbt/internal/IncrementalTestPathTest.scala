package sbt.internal

import hedgehog.*
import hedgehog.runner.*
import java.nio.file.{ Files, Path }
import _root_.sbt.internal.inc.{ MappedDirectory, MappedFileConverter }
import xsbti.{ FileConverter, VirtualFile, VirtualFileRef }
import _root_.sbt.io.IO
import scala.collection.immutable.ListMap
import scala.util.Try

object IncrementalTestPathTest extends Properties:
  override def tests: List[Test] = List(
    example("project paths retain the configured converter's IDs", matchingIds),
    example("project paths remain portable when the build moves", relocatedIds),
    example("unmapped paths retain the machine-path policy", unmappedIds),
    example("macOS root aliases retain the configured converter's IDs", macAliases),
    example("custom converters preserve their IDs and receive the path", customConverter),
    example("mapped converter subclasses preserve overridden behavior", subclasses),
  )

  private def matchingIds: Result = IO.withTemporaryDirectory: tmp =>
    val base = tmp.toPath
    val project = Files.createDirectories(base.resolve("nested/core"))
    Files.writeString(project.resolve("source.scala"), "object Example")
    val mappings = List(
      Map("BASE" -> base),
      ListMap("BASE" -> base, "NESTED" -> project.getParent),
      ListMap("NESTED" -> project.getParent, "BASE" -> base),
      ListMap("FIRST" -> base, "ALIAS" -> base),
      ListMap("ALIAS" -> base, "FIRST" -> base),
    )
    Result.all(for
      roots <- mappings
      path <- List(base, project, project.resolveSibling("sibling"), project.resolve("missing"))
    yield
      Result.assert(
        IncrementalTest.projectPathId(path, MappedFileConverter(roots, false)) ==
          MappedFileConverter(roots, false).toVirtualFile(path).id
      )
    )

  private def relocatedIds: Result = IO.withTemporaryDirectory: tmp =>
    val first = Files.createDirectories(tmp.toPath.resolve("one/core"))
    val second = Files.createDirectories(tmp.toPath.resolve("two/core"))
    Files.writeString(first.resolve("one.scala"), "object One")
    Files.writeString(second.resolve("two.scala"), "object Two")
    val id = IncrementalTest.projectPathId(first, MappedFileConverter(Map("BASE" -> first.getParent), false))
    Result.all(List(
      Result.assert(id == "${BASE}/core"),
      Result.assert(
        id == IncrementalTest.projectPathId(second, MappedFileConverter(Map("BASE" -> second.getParent), false))
      ),
    ))

  private def unmappedIds: Result = IO.withTemporaryDirectory: tmp =>
    val path = Files.createDirectories(tmp.toPath.resolve("project"))
    val roots = Map("OTHER" -> tmp.toPath.resolve("other"))
    Result.all(List(
      Result.assert(
        IncrementalTest.projectPathId(path, MappedFileConverter(roots, true)) ==
          MappedFileConverter(roots, true).toVirtualFile(path).id
      ),
      Result.assert(Try(IncrementalTest.projectPathId(path, MappedFileConverter(roots, false))).isFailure),
      Result.assert(Try(MappedFileConverter(roots, false).toVirtualFile(path)).isFailure),
    ))

  private def macAliases: Result =
    if !System.getProperty("os.name").toLowerCase.contains("mac") then Result.success
    else
      val roots = Map("TMP" -> Path.of("/var/tmp"))
      val path = Path.of("/private/var/tmp/sbt-path-id-missing")
      Result.all(List(
        Result.assert(
          IncrementalTest.projectPathId(path, MappedFileConverter(roots, false)) ==
            MappedFileConverter(roots, false).toVirtualFile(path).id
        ),
        Result.assert(
          IncrementalTest.projectPathId(path, MappedFileConverter(roots, false)) ==
            IncrementalTest.projectPathId(Path.of("/var/tmp/sbt-path-id-missing"), MappedFileConverter(roots, false))
        ),
      ))

  private def customConverter: Result = IO.withTemporaryDirectory: tmp =>
    val path = tmp.toPath
    var received = List.empty[Path]
    val converter = new FileConverter:
      override def toPath(ref: VirtualFileRef): Path = path
      override def toVirtualFile(input: Path): VirtualFile =
        received = received :+ input
        MappedDirectory("custom-project-id", Map.empty, Nil)
    Result.all(List(
      Result.assert(IncrementalTest.projectPathId(path, converter) == "custom-project-id"),
      Result.assert(received == List(path)),
    ))

  private def subclasses: Result = IO.withTemporaryDirectory: tmp =>
    val path = tmp.toPath
    val roots = Map("BASE" -> path)
    var fileCalls = 0
    var directoryCalls = 0
    val fileConverter = new MappedFileConverter(roots, false):
      override def toVirtualFile(input: Path): VirtualFile =
        fileCalls += 1
        MappedDirectory("overridden-file-id", roots, Nil)
    val directoryConverter = new MappedFileConverter(roots, false):
      override def toDirectory(input: Path, encodedPath: String): MappedDirectory =
        directoryCalls += 1
        MappedDirectory("overridden-directory-id", roots, Nil)
    Result.all(List(
      Result.assert(IncrementalTest.projectPathId(path, fileConverter) == "overridden-file-id"),
      Result.assert(fileCalls == 1),
      Result.assert(
        IncrementalTest.projectPathId(path, directoryConverter) == "overridden-directory-id"
      ),
      Result.assert(directoryCalls == 1),
    ))
