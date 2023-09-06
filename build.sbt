organization := "io.github.tpataky"

name := "duckling"

version := "0.0.2"

val scala2 = "2.13.8"
val scala3 = "3.3.0"

scalaVersion := scala2

crossScalaVersions := Seq(scala2, scala3)

versionScheme := Some("semver-spec")

licenses := Seq("APL2" -> url("http://www.apache.org/licenses/LICENSE-2.0.txt"))

publishMavenStyle := true
publishTo := sonatypePublishToBundle.value
sonatypeCredentialHost := "s01.oss.sonatype.org"
sonatypeRepository := "https://s01.oss.sonatype.org/service/local"
sonatypeProjectHosting := Some(xerial.sbt.Sonatype.GitHubHosting("tpataky", "duckling", "tpataky@gmail.com"))

scalacOptions ++= {
  CrossVersion.partialVersion(scalaVersion.value) match {
    case Some((3, _)) =>
      Seq(
        "-deprecation",
        "-encoding",
        "UTF-8",
        "-feature",
        "-unchecked",
        "-language:existentials",
        "-language:implicitConversions",
        "-language:higherKinds",
        "-Wunused:all",
        "-source:3.0-migration"
      )
    case _ =>
      Seq(
        "-deprecation",
        "-encoding",
        "UTF-8",
        "-feature",
        "-unchecked",
        "-language:existentials",
        "-language:implicitConversions",
        "-language:higherKinds",
        "-Werror",
        "-Wdead-code",
        "-Wextra-implicit",
        "-Wmacros:after",
        "-Wunused:imports",
        // "-Wunused:locals",
        "-Wunused:patvars",
        "-Wunused:privates",
        "-Wvalue-discard",
        "-Wconf:any:warning-verbose",
        "-Xlint:-byname-implicit,_",
        "-Xsource:2.13.0",
        "-Ypatmat-exhaust-depth",
        "off"
      )
  }
}

libraryDependencies := Seq(
  "org.typelevel" %% "cats-core" % "2.10.0",
  "org.scalameta" %% "munit" % "0.7.29" % Test,
  "org.openjdk.jmh" % "jmh-core" % (Jmh / version).value % Test,
  "org.openjdk.jmh" % "jmh-generator-bytecode" % (Jmh / version).value % Test,
  "org.openjdk.jmh" % "jmh-generator-reflection" % (Jmh / version).value % Test
)

enablePlugins(JmhPlugin)

Jmh / sourceDirectory := (Test / sourceDirectory).value
Jmh / classDirectory := (Test / classDirectory).value
Jmh / dependencyClasspath := (Test / dependencyClasspath).value
// rewire tasks, so that 'bench/Jmh/run' automatically invokes 'bench/Jmh/compile' (otherwise a clean 'bench/Jmh/run' would fail)
Jmh / compile := (Jmh / compile).dependsOn(Test / compile).value
Jmh / run := (Jmh / run).dependsOn(Jmh / compile).evaluated
