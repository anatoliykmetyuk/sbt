scalaVersion := "3.3.4"

Test / fork := true

libraryDependencies += "org.scalatest" %% "scalatest-funsuite" % "3.2.19" % Test
libraryDependencies += "com.google.code.gson" % "gson" % "2.13.2"

