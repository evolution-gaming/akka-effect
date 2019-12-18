import sbt._

object Dependencies {

  val scalatest                   = "org.scalatest"       %% "scalatest"                 % "3.1.0"
  val `cats-helper`               = "com.evolutiongaming" %% "cats-helper"               % "1.2.0"
  val `executor-tools`            = "com.evolutiongaming" %% "executor-tools"            % "1.0.2"
  val `akka-persistence-inmemory` = "com.github.dnvriend" %% "akka-persistence-inmemory" % "2.5.15.2"

  object Cats {
    private val version = "2.0.0"
    val core   = "org.typelevel" %% "cats-core"   % version
    val kernel = "org.typelevel" %% "cats-kernel" % version
    val macros = "org.typelevel" %% "cats-macros" % version
    val effect = "org.typelevel" %% "cats-effect" % "2.1.0"
  }

  object Akka {
    private val version = "2.5.25"
    val actor               = "com.typesafe.akka" %% "akka-actor"             % version
    val testkit             = "com.typesafe.akka" %% "akka-testkit"           % version
    val stream              = "com.typesafe.akka" %% "akka-stream"            % version
    val persistence         = "com.typesafe.akka" %% "akka-persistence"       % version
    val `persistence-query` = "com.typesafe.akka" %% "akka-persistence-query" % version
    val `persistence-tck`   = "com.typesafe.akka" %% "akka-persistence-tck"   % version
    val slf4j               = "com.typesafe.akka" %% "akka-slf4j"             % version
  }

  object Logback {
    private val version = "1.2.3"
    val core    = "ch.qos.logback" % "logback-core"    % version
    val classic = "ch.qos.logback" % "logback-classic" % version
  }

  object Slf4j {
    private val version = "1.7.26"
    val api                = "org.slf4j" % "slf4j-api"        % version
    val `log4j-over-slf4j` = "org.slf4j" % "log4j-over-slf4j" % version
  }
}