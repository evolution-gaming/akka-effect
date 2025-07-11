import Dependencies.*
import com.typesafe.tools.mima.core.{MissingClassProblem, ProblemFilters}

lazy val commonSettings = Seq(
  organization         := "com.evolutiongaming",
  organizationName     := "Evolution",
  organizationHomepage := Some(url("http://evolution.com")),
  homepage             := Some(url("http://github.com/evolution-gaming/akka-effect")),
  startYear            := Some(2019),
  crossScalaVersions   := Seq("2.13.16", "3.3.6"),
  scalaVersion         := crossScalaVersions.value.head,
  Compile / doc / scalacOptions ++= Seq("-groups", "-implicits", "-no-link-warnings"),
  Compile / doc / scalacOptions -= "-Xfatal-warnings",
  scalacOptions ++= Seq(
    "-release:17",
    "-deprecation",
  ),
  scalacOptions ++= crossSettings(
    scalaVersion = scalaVersion.value,
    if2 = Seq(
      "-Xsource:3",
    ),
    // Good compiler options for Scala 2.13 are coming from com.evolution:sbt-scalac-opts-plugin:0.0.9,
    // but its support for Scala 3 is limited, especially what concerns linting options.
    //
    // If Scala 3 is made the primary target, good linting scalac options for it should be added first.
    if3 = Seq(
      "-Ykind-projector:underscores",

      // disable new brace-less syntax:
      // https://alexn.org/blog/2022/10/24/scala-3-optional-braces/
      "-no-indent",

      // improve error messages:
      "-explain",
      "-explain-types",
    ),
  ),
  publishTo              := Some(Resolver.evolutionReleases),
  versionPolicyIntention := Compatibility.BinaryCompatible, // sbt-version-policy
  versionScheme          := Some("semver-spec"),
  libraryDependencies ++= crossSettings(
    scalaVersion = scalaVersion.value,
    if2 = Seq(compilerPlugin(`kind-projector` cross CrossVersion.full)),
    if3 = Nil,
  ),
  licenses := Seq(("MIT", url("https://opensource.org/licenses/MIT"))),
  // TEMPORARY disable mima checks for new Scala 3 modules
  versionCheck / skip       := scalaVersion.value == "3.3.6",
  versionPolicyCheck / skip := scalaVersion.value == "3.3.6",
)

val alias =
  addCommandAlias("build", "+all compile test") ++
    addCommandAlias("fmt", "+all scalafmtAll scalafmtSbt") ++
    // `check` is called with `+` in release workflow
    addCommandAlias("check", "all versionPolicyCheck Compile/doc scalafmtCheckAll scalafmtSbtCheck")

lazy val root = project
  .in(file("."))
  .settings(name := "akka-effect")
  .settings(commonSettings)
  .settings(publish / skip := true)
  .settings(alias)
  .aggregate(
    actor,
    `actor-tests`,
    testkit,
    persistence,
    `persistence-api`,
    eventsourcing,
    cluster,
    `cluster-sharding`,
  )

lazy val actor = project
  .in(file("actor"))
  .settings(name := "akka-effect-actor")
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(
      Akka.actor,
      Akka.slf4j   % Test,
      Akka.testkit % Test,
      Cats.core,
      CatsEffect.effect,
      Logback.classic          % Test,
      Logback.core             % Test,
      Slf4j.api                % Test,
      Slf4j.`log4j-over-slf4j` % Test,
      `cats-helper`,
      scalatest % Test,
    ),
  )

lazy val `actor-tests` = project
  .in(file("actor-tests"))
  .settings(name := "akka-effect-actor-tests")
  .settings(commonSettings)
  .settings(publish / skip := true)
  .dependsOn(actor % "test->test;compile->compile", testkit % "test->test;test->compile")
  .settings(
    libraryDependencies ++= Seq(
      Akka.testkit % Test,
    ),
  )

lazy val testkit = project
  .in(file("testkit"))
  .settings(name := "akka-effect-testkit")
  .settings(commonSettings)
  .dependsOn(actor)
  .settings(
    libraryDependencies ++= Seq(
      Akka.testkit % Test,
      scalatest    % Test,
    ),
  )

lazy val `persistence-api` = project
  .in(file("persistence-api"))
  .settings(name := "akka-effect-persistence-api")
  .settings(commonSettings)
  .dependsOn(
    actor         % "test->test;compile->compile",
    testkit       % "test->test;test->compile",
    `actor-tests` % "test->test",
  )
  .settings(
    libraryDependencies ++= Seq(
      Cats.core,
      CatsEffect.effect,
      `cats-helper`,
      sstream,
      Akka.slf4j   % Test,
      Akka.testkit % Test,
      scalatest    % Test,
    ),
  )

lazy val persistence = project
  .in(file("persistence"))
  .settings(name := "akka-effect-persistence")
  .settings(commonSettings)
  .dependsOn(
    `persistence-api` % "test->test;compile->compile",
    actor             % "test->test;compile->compile",
    testkit           % "test->test;test->compile",
    `actor-tests`     % "test->test",
  )
  .settings(
    libraryDependencies ++= Seq(
      Akka.actor,
      Akka.stream,
      Akka.persistence,
      Akka.`persistence-query`,
      Akka.`persistence-testkit` % Test,
      Akka.slf4j                 % Test,
      Akka.testkit               % Test,
      Cats.core,
      CatsEffect.effect,
      `cats-helper`,
      smetrics,
      scalatest % Test,
    ),
    libraryDependencies ++= crossSettings(
      scalaVersion = scalaVersion.value,
      if2 = List(Pureconfig.Pureconfig),
      if3 = List(Pureconfig.Scala3.Core, Pureconfig.Scala3.Generic),
    ),
  )

lazy val eventsourcing = project
  .in(file("eventsourcing"))
  .settings(name := "akka-effect-eventsourcing")
  .settings(commonSettings)
  .dependsOn(persistence % "test->test;compile->compile")
  .settings(
    libraryDependencies ++= Seq(
      Akka.stream,
      retry,
    ),
    libraryDependencies ++= crossSettings(
      scalaVersion = scalaVersion.value,
      if2 = List(Pureconfig.Pureconfig),
      if3 = List(Pureconfig.Scala3.Core, Pureconfig.Scala3.Generic),
    ),
  )

lazy val cluster = project
  .in(file("cluster"))
  .settings(name := "akka-effect-cluster")
  .settings(commonSettings)
  .dependsOn(actor % "test->test;compile->compile", testkit % "test->test;test->compile", `actor-tests` % "test->test")
  .settings(
    libraryDependencies ++= Seq(
      Akka.cluster,
      Akka.`cluster-typed`,
    ),
    libraryDependencies ++= crossSettings(
      scalaVersion = scalaVersion.value,
      if2 = List(Pureconfig.Pureconfig),
      if3 = List(Pureconfig.Scala3.Core, Pureconfig.Scala3.Generic),
    ),
  )

lazy val `cluster-sharding` = project
  .in(file("cluster-sharding"))
  .settings(name := "akka-effect-cluster-sharding")
  .settings(commonSettings)
  .dependsOn(
    cluster     % "test->test;compile->compile",
    persistence % "test->test;compile->compile",
  )
  .settings(
    libraryDependencies ++= Seq(
      Akka.`cluster-sharding`,
    ),
  )

def crossSettings[T](scalaVersion: String, if3: T, if2: T): T =
  scalaVersion match {
    case version if version.startsWith("3") => if3
    case _                                  => if2
  }

ThisBuild / mimaBinaryIssueFilters ++= Seq(
  // add mima check exceptions here, like:
  ProblemFilters.exclude[MissingClassProblem](
    "com.evolutiongaming.akkaeffect.cluster.sharding.ClusterShardingLocal$RegionMsg$2$Rebalance$",
  ),
  ProblemFilters.exclude[MissingClassProblem](
    "com.evolutiongaming.akkaeffect.cluster.sharding.ClusterShardingLocal$RegionMsg$2$State$",
  ),
)
