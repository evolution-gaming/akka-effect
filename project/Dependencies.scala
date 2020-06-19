import sbt._

object Dependencies {
  
  val scalatest                   = "org.scalatest"         %% "scalatest"                 % "3.2.0"
  val `cats-helper`               = "com.evolutiongaming"   %% "cats-helper"               % "2.0.3"
  val `executor-tools`            = "com.evolutiongaming"   %% "executor-tools"            % "1.0.2"
  val retry                       = "com.evolutiongaming"   %% "retry"                     % "1.0.4"
  val `akka-persistence-inmemory` = "com.github.dnvriend"   %% "akka-persistence-inmemory" % "2.5.15.2"
  val `kind-projector`            = "org.typelevel"          % "kind-projector"            % "0.11.0"
  val pureconfig                  = "com.github.pureconfig" %% "pureconfig"                % "0.12.3"
  val smetrics                    = "com.evolutiongaming"   %% "smetrics"                  % "0.1.1"

  object Cats {
    private val version = "2.1.1"
    val core   = "org.typelevel" %% "cats-core"   % version
    val kernel = "org.typelevel" %% "cats-kernel" % version
    val macros = "org.typelevel" %% "cats-macros" % version
    val effect = "org.typelevel" %% "cats-effect" % "2.1.3"
  }

  object Akka {
    private val version = "2.5.31"
    val actor               = "com.typesafe.akka" %% "akka-actor"             % version
    val testkit             = "com.typesafe.akka" %% "akka-testkit"           % version
    val stream              = "com.typesafe.akka" %% "akka-stream"            % version
    val persistence         = "com.typesafe.akka" %% "akka-persistence"       % version
    val `persistence-query` = "com.typesafe.akka" %% "akka-persistence-query" % version
    val `persistence-tck`   = "com.typesafe.akka" %% "akka-persistence-tck"   % version
    val slf4j               = "com.typesafe.akka" %% "akka-slf4j"             % version
  }

  object SafeAkka {
    private val version = "3.0.0"
    val actor                 = "com.evolutiongaming" %% "safe-actor"               % version
    val persistence           = "com.evolutiongaming" %% "safe-persistence"         % version
    val `persistence-async`   = "com.evolutiongaming" %% "safe-persistence-async"   % version
    val `persistence-testkit` = "com.evolutiongaming" %% "safe-persistence-testkit" % version
  }

  object Logback {
    private val version = "1.2.3"
    val core    = "ch.qos.logback" % "logback-core"    % version
    val classic = "ch.qos.logback" % "logback-classic" % version
  }

  object Slf4j {
    private val version = "1.7.30"
    val api                = "org.slf4j" % "slf4j-api"        % version
    val `log4j-over-slf4j` = "org.slf4j" % "log4j-over-slf4j" % version
  }
}