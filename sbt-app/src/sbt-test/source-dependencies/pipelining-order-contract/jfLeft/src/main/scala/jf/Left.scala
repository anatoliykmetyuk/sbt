/*
 * sbt
 * Copyright 2026, Scala Center, and the sbt contributors.
 * Licensed under Apache License 2.0 (see LICENSE).
 */

package jf
class Left { def value: Int = new UpJava().value() + new UpScala().value + new api.JavaBase().value() + new api.ScalaBase().value + new api.jf.OnlyJava().value() }
