import sbt._

object Dependencies {

  val scalatest                   = "org.scalatest"         %% "scalatest"                 % "3.2.18"
  val `cats-helper`               = "com.evolutiongaming"   %% "cats-helper"               % "3.6.0"
  val `executor-tools`            = "com.evolutiongaming"   %% "executor-tools"            % "1.0.4"
  val retry                       = "com.evolutiongaming"   %% "retry"                     % "3.0.2"
  val `akka-persistence-inmemory` = "com.github.dnvriend"   %% "akka-persistence-inmemory" % "2.5.15.2"
  val `kind-projector`            = "org.typelevel"          % "kind-projector"            % "0.13.3"
  val pureconfig                  = "com.github.pureconfig" %% "pureconfig"                % "0.17.3"
  val smetrics                    = "com.evolutiongaming"   %% "smetrics"                  % "2.2.0"
  val sstream                     = "com.evolutiongaming"   %% "sstream"                   % "1.0.1"

  object Cats {
    private val version = "2.10.0"
    val core            = "org.typelevel" %% "cats-core"   % version
    val kernel          = "org.typelevel" %% "cats-kernel" % version
    val macros          = "org.typelevel" %% "cats-macros" % version
  }

  object CatsEffect {
    private val version = "3.4.11"
    val effect          = "org.typelevel" %% "cats-effect" % version
  }

  object Akka {
    private val version     = "2.6.20"
    val actor               = "com.typesafe.akka" %% "akka-actor"             % version
    val testkit             = "com.typesafe.akka" %% "akka-testkit"           % version
    val stream              = "com.typesafe.akka" %% "akka-stream"            % version
    val persistence         = "com.typesafe.akka" %% "akka-persistence"       % version
    val `persistence-query` = "com.typesafe.akka" %% "akka-persistence-query" % version
    val `persistence-tck`   = "com.typesafe.akka" %% "akka-persistence-tck"   % version
    val cluster             = "com.typesafe.akka" %% "akka-cluster"           % version
    val `cluster-sharding`  = "com.typesafe.akka" %% "akka-cluster-sharding"  % version
    val `cluster-tools`     = "com.typesafe.akka" %% "akka-cluster-tools"     % version
    val slf4j               = "com.typesafe.akka" %% "akka-slf4j"             % version
  }

  object Logback {
    private val version = "1.5.6"
    val core            = "ch.qos.logback" % "logback-core"    % version
    val classic         = "ch.qos.logback" % "logback-classic" % version
  }

  object Slf4j {
    private val version    = "2.0.13"
    val api                = "org.slf4j" % "slf4j-api"        % version
    val `log4j-over-slf4j` = "org.slf4j" % "log4j-over-slf4j" % version
  }
}
