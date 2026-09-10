/*
 * sbt
 * Copyright 2026, Scala Center, and the sbt contributors.
 * Licensed under Apache License 2.0 (see LICENSE).
 */

package sftest
object Main {
  def main(args: Array[String]): Unit = {
    val expected = args.headOption.map(_.toInt).getOrElse(44)
    assert(Class.forName("sftest.TailJava").getMethod("value").invoke(Class.forName("sftest.TailJava").getDeclaredConstructor().newInstance()).asInstanceOf[Int] == expected)
    assert(new Diamond().value == args.lift(1).map(_.toInt).getOrElse(expected - 4))
  }
}
