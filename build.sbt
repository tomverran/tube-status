name := "tubestatus"

version := "1.0"

scalaVersion := "2.12.1"

enablePlugins(JavaServerAppPackaging, SystemdPlugin)
enablePlugins(DebianPlugin)

libraryDependencies += "com.typesafe.akka" %% "akka-http" % "10.0.9"

libraryDependencies ++= Seq(
  "com.47deg" %% "classy-core"            % "0.4.0",
  "com.47deg" %% "classy-config-typesafe" % "0.4.0",
  "com.47deg" %% "classy-generic"         % "0.4.0"
)

val circeVersion = "0.8.0"

libraryDependencies ++= Seq(
  "io.circe" %% "circe-core",
  "io.circe" %% "circe-generic",
  "io.circe" %% "circe-parser"
).map(_ % circeVersion)


libraryDependencies ++= Seq(
  "software.amazon.awssdk" % "cloudwatch" % "2.0.0-preview-1"
)

libraryDependencies += "de.heikoseeberger" %% "akka-http-circe" % "1.17.0"
