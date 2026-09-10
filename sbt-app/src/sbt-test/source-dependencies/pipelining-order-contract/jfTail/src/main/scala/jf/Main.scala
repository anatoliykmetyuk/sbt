/*
 * sbt
 * Copyright 2026, Scala Center, and the sbt contributors.
 * Licensed under Apache License 2.0 (see LICENSE).
 */

package jf
object Main {
  def main(args: Array[String]): Unit = {
    val expected = args.headOption.map(_.toInt).getOrElse(67)
    assert(new TailJava().value() == expected)
    assert(new Diamond().value == args.lift(1).map(_.toInt).getOrElse(expected - 7))
  }
}
