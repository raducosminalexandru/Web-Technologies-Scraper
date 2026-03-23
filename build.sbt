scalaVersion := "2.13.18"

name := "web-technologies-scraper"
organization := "ch.epfl.scala"
version := "1.0"

libraryDependencies += "org.scala-lang.modules" %% "scala-parser-combinators" % "2.3.0"

libraryDependencies ++= Seq(
  "org.apache.spark" %% "spark-sql" % "3.5.1" % "provided"
)

fork := true

javaOptions ++= Seq(
  "--add-opens=java.base/java.lang=ALL-UNNAMED",
  "--add-opens=java.base/java.lang.invoke=ALL-UNNAMED",
  "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
  "--add-opens=java.base/java.io=ALL-UNNAMED",
  "--add-opens=java.base/java.net=ALL-UNNAMED",
  "--add-opens=java.base/java.nio=ALL-UNNAMED",
  "--add-opens=java.base/java.util=ALL-UNNAMED",
  "--add-opens=java.base/java.util.concurrent=ALL-UNNAMED",
  "--add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED",
  "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
  "--add-opens=java.base/sun.nio.cs=ALL-UNNAMED",
  "--add-opens=java.base/sun.security.action=ALL-UNNAMED",
  "--add-opens=java.base/sun.util.calendar=ALL-UNNAMED"
)

libraryDependencies += "com.softwaremill.sttp.client4" %% "core" % "4.0.19"

libraryDependencies += "org.jsoup" % "jsoup" % "1.17.2"

libraryDependencies += "org.scala-lang.modules" %% "scala-parallel-collections" % "1.0.4"