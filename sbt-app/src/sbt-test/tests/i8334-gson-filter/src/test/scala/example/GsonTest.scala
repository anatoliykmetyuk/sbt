package example

import org.scalatest.funsuite.AnyFunSuite

class GsonTest extends AnyFunSuite {
  test("forked tests use project gson, not boot gson") {
    val src = classOf[com.google.gson.Gson].getProtectionDomain.getCodeSource
    assert(src != null, "Gson code source must be available")
    val url = src.getLocation.toString
    assert(
      url.contains("gson-2.13.2"),
      s"expected project gson 2.13.2 on classpath, got: $url"
    )
    assert(
      !url.contains("/.sbt/boot/"),
      s"should not load gson from .sbt/boot, got: $url"
    )
  }
}

