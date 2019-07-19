import Dependencies._


lazy val commonSettings = Seq(
  organization := "com.evolutiongaming",
  homepage := Some(new URL("http://github.com/evolution-gaming/akka-effect")),
  startYear := Some(2019),
  organizationName := "Evolution Gaming",
  organizationHomepage := Some(url("http://evolutiongaming.com")),
  bintrayOrganization := Some("evolutiongaming"),
  scalaVersion := crossScalaVersions.value.head,
  crossScalaVersions := Seq("2.12.8"),
  scalacOptions in(Compile, doc) ++= Seq("-groups", "-implicits", "-no-link-warnings"),
  resolvers += Resolver.bintrayRepo("evolutiongaming", "maven"),
  licenses := Seq(("MIT", url("https://opensource.org/licenses/MIT"))),
  releaseCrossBuild := true/*,
  testOptions in Test ++= Seq(Tests.Argument(TestFrameworks.ScalaTest, "-oUDNCXEHLOPQRM"))*/)


lazy val root = (project in file(".")
  settings (name := "akka-effect")
  settings commonSettings
  settings (skip in publish := true)
  aggregate(
    actor,
    persistence))

lazy val actor = (project in file("actor")
  settings (name := "akka-effect-actor")
  settings commonSettings
  settings (libraryDependencies ++= Seq(
    Akka.actor,
    Akka.slf4j   % Test,
    Akka.testkit % Test,
    Cats.core,
    Cats.effect,
    Logback.classic % Test,
    Logback.core    % Test,
    Slf4j.api                % Test,
    Slf4j.`log4j-over-slf4j` % Test,
    `cats-helper`,
    `executor-tools`,
    scalatest % Test)))

lazy val persistence = (project in file("persistence")
  settings (name := "akka-effect-persistence")
  settings commonSettings
  dependsOn actor
  settings (libraryDependencies ++= Seq(
    Cats.core,
    Cats.effect,
    `cats-helper`,
    scalatest % Test)))