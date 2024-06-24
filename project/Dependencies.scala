import sbt._

object Dependencies {

  val scalatest                   = "org.scalatest"         %% "scalatest"                 % "3.2.19"
  val `cats-helper`               = "com.evolutiongaming"   %% "cats-helper"               % "3.10.5"
  val retry                       = "com.evolutiongaming"   %% "retry"                     % "3.1.0"
  val `akka-persistence-inmemory` = "com.github.dnvriend"   %% "akka-persistence-inmemory" % "2.5.15.2"
  val `kind-projector`            = "org.typelevel"          % "kind-projector"            % "0.13.3"
  val pureconfig                  = "com.github.pureconfig" %% "pureconfig"                % "0.17.7"
  val smetrics                    = "com.evolutiongaming"   %% "smetrics"                  % "2.2.0"
  val sstream                     = "com.evolutiongaming"   %% "sstream"                   % "1.0.2"

  object Cats {
    private val version = "2.12.0"
    val core            = "org.typelevel" %% "cats-core" % version
  }

  object CatsEffect {
    private val version = "3.5.4"
    val effect          = "org.typelevel" %% "cats-effect" % version
  }

  object Akka {
    private val version     = "2.6.21"
    val actor               = "com.typesafe.akka" %% "akka-actor"             % version
    val testkit             = "com.typesafe.akka" %% "akka-testkit"           % version
    val stream              = "com.typesafe.akka" %% "akka-stream"            % version
    val persistence         = "com.typesafe.akka" %% "akka-persistence"       % version
    val `persistence-query` = "com.typesafe.akka" %% "akka-persistence-query" % version
    val cluster             = "com.typesafe.akka" %% "akka-cluster"           % version
    val `cluster-sharding`  = "com.typesafe.akka" %% "akka-cluster-sharding"  % version
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
