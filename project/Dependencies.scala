import sbt._

object Dependencies {

  val scalatest        = "org.scalatest"       %% "scalatest"      % "3.2.19"
  val `cats-helper`    = "com.evolutiongaming" %% "cats-helper"    % "3.12.2"
  val retry            = "com.evolutiongaming" %% "retry"          % "3.1.0"
  val `kind-projector` = "org.typelevel"        % "kind-projector" % "0.13.4"
  val smetrics         = "com.evolutiongaming" %% "smetrics"       % "2.4.4"
  val sstream          = "com.evolutiongaming" %% "sstream"        % "1.1.0"

  object Pureconfig {
    private val version = "0.17.10"
    val Pureconfig      = "com.github.pureconfig" %% "pureconfig" % version
    object Scala3 {
      val Core    = "com.github.pureconfig" %% "pureconfig-core"           % version
      val Generic = "com.github.pureconfig" %% "pureconfig-generic-scala3" % version
    }
  }

  object Cats {
    private val version = "2.13.0"
    val core            = "org.typelevel" %% "cats-core" % version
  }

  object CatsEffect {
    private val version = "3.6.3"
    val effect          = "org.typelevel" %% "cats-effect" % version
  }

  object Akka {
    private val version       = "2.6.21"
    val actor                 = "com.typesafe.akka" %% "akka-actor"               % version
    val testkit               = "com.typesafe.akka" %% "akka-testkit"             % version
    val stream                = "com.typesafe.akka" %% "akka-stream"              % version
    val persistence           = "com.typesafe.akka" %% "akka-persistence"         % version
    val `persistence-query`   = "com.typesafe.akka" %% "akka-persistence-query"   % version
    val `persistence-testkit` = "com.typesafe.akka" %% "akka-persistence-testkit" % version
    val cluster               = "com.typesafe.akka" %% "akka-cluster"             % version
    val `cluster-sharding`    = "com.typesafe.akka" %% "akka-cluster-sharding"    % version
    val `cluster-typed`       = "com.typesafe.akka" %% "akka-cluster-typed"       % version
    val slf4j                 = "com.typesafe.akka" %% "akka-slf4j"               % version
  }

  object Logback {
    private val version = "1.5.32"
    val core            = "ch.qos.logback" % "logback-core"    % version
    val classic         = "ch.qos.logback" % "logback-classic" % version
  }

  object Slf4j {
    private val version    = "2.0.17"
    val api                = "org.slf4j" % "slf4j-api"        % version
    val `log4j-over-slf4j` = "org.slf4j" % "log4j-over-slf4j" % version
  }
}
