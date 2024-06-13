import Dependencies._

lazy val commonSettings = Seq(
  organization         := "com.evolutiongaming",
  organizationName     := "Evolution",
  organizationHomepage := Some(url("http://evolution.com")),
  homepage             := Some(url("http://github.com/evolution-gaming/akka-effect")),
  startYear            := Some(2019),
  scalaVersion         := "2.13.14",
  Compile / doc / scalacOptions ++= Seq("-groups", "-implicits", "-no-link-warnings"),
  scalacOptions ++= Seq("-release:17", "-Xsource:3", "-deprecation"),
  releaseCrossBuild      := true,
  publishTo              := Some(Resolver.evolutionReleases),
  versionPolicyIntention := Compatibility.BinaryCompatible, // sbt-version-policy
  versionScheme          := Some("semver-spec"),
  versionPolicyCheck     := false,                          // TODO: delete after releasing v5.0.0
  libraryDependencies += compilerPlugin(`kind-projector` cross CrossVersion.full),
  licenses := Seq(("MIT", url("https://opensource.org/licenses/MIT"))),
)

val alias: Seq[sbt.Def.Setting[_]] =
  addCommandAlias("fmt", "scalafixEnable; scalafixAll; all scalafmtAll scalafmtSbt") ++
    addCommandAlias(
      "check",
      "all Compile/doc versionPolicyCheck scalafmtCheckAll scalafmtSbtCheck; scalafixEnable; scalafixAll --check",
    ) ++
    addCommandAlias("build", "all compile test")

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
      Akka.slf4j   % Test,
      Akka.testkit % Test,
      Cats.core,
      CatsEffect.effect,
      `cats-helper`,
      pureconfig,
      smetrics,
      scalatest                   % Test,
      `akka-persistence-inmemory` % Test,
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
  )

lazy val cluster = project
  .in(file("cluster"))
  .settings(name := "akka-effect-cluster")
  .settings(commonSettings)
  .dependsOn(actor % "test->test;compile->compile", testkit % "test->test;test->compile", `actor-tests` % "test->test")
  .settings(
    libraryDependencies ++= Seq(
      Akka.cluster,
      pureconfig,
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
