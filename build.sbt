import Dependencies._


lazy val commonSettings = Seq(
  organization := "com.evolutiongaming",
  homepage := Some(new URL("http://github.com/evolution-gaming/akka-effect")),
  startYear := Some(2019),
  organizationName := "Evolution Gaming",
  organizationHomepage := Some(url("http://evolutiongaming.com")),
  scalaVersion := crossScalaVersions.value.head,
  crossScalaVersions := Seq("2.13.6", "2.12.13"),
  Compile / doc / scalacOptions ++= Seq("-groups", "-implicits", "-no-link-warnings"),
  publishTo := Some(Resolver.evolutionReleases),
  licenses := Seq(("MIT", url("https://opensource.org/licenses/MIT"))),
  releaseCrossBuild := true,
  scalacOptsFailOnWarn := Some(false),
  /*testOptions in Test ++= Seq(Tests.Argument(TestFrameworks.ScalaTest, "-oUDNCXEHLOPQRM"))*/
  libraryDependencies += compilerPlugin(`kind-projector` cross CrossVersion.full))


lazy val root = (project in file(".")
  settings (name := "akka-effect")
  settings commonSettings
  settings (publish / skip := true)
  aggregate(
    actor,
    `actor-tests`,
    testkit,
    persistence,
    eventsourcing,
    cluster,
    `cluster-sharding`))

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
    Logback.core % Test,
    Slf4j.api % Test,
    Slf4j.`log4j-over-slf4j` % Test,
    `cats-helper`,
    `executor-tools`,
    scalatest % Test)))

lazy val `actor-tests` = (project in file("actor-tests")
  settings (name := "akka-effect-actor-tests")
  settings commonSettings
  settings (publish / skip := true)
  dependsOn(actor % "test->test;compile->compile", testkit % "test->test;test->compile")
  settings (libraryDependencies ++= Seq(
    Akka.testkit % Test)))

lazy val testkit = (project in file("testkit")
  settings (name := "akka-effect-testkit")
  settings commonSettings
  dependsOn actor
  settings (libraryDependencies ++= Seq(
    Akka.testkit % Test,
    scalatest % Test)))

lazy val persistence = (project in file("persistence")
  settings (name := "akka-effect-persistence")
  settings commonSettings
  dependsOn(
    actor         % "test->test;compile->compile",
    testkit       % "test->test;test->compile",
    `actor-tests` % "test->test")
  settings (
    libraryDependencies ++= Seq(
      Akka.actor,
      Akka.stream,
      Akka.persistence,
      Akka.`persistence-query`,
      Akka.slf4j   % Test,
      Akka.testkit % Test,
      Cats.core,
      Cats.effect,
      `cats-helper`,
      pureconfig,
      smetrics,
      scalatest % Test,
      `akka-persistence-inmemory` % Test)))

lazy val eventsourcing = (project in file("eventsourcing")
  settings (name := "akka-effect-eventsourcing")
  settings commonSettings
  dependsOn persistence % "test->test;compile->compile"
  settings (
    libraryDependencies ++= Seq(
      Akka.stream,
      retry)))

lazy val cluster = (project in file("cluster")
  settings (name := "akka-effect-cluster")
  settings commonSettings
  dependsOn(
    actor         % "test->test;compile->compile",
    testkit       % "test->test;test->compile",
    `actor-tests` % "test->test")
  settings (
    libraryDependencies ++= Seq(
      Akka.cluster,
      pureconfig)))

lazy val `cluster-sharding` = (project in file("cluster-sharding")
  settings (name := "akka-effect-cluster-sharding")
  settings commonSettings
  dependsOn (
    cluster % "test->test;compile->compile",
    persistence % "test->test;compile->compile")
  settings (
    libraryDependencies ++= Seq(
      Akka.`cluster-sharding`)))