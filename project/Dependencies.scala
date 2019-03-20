import sbt._

object Dependencies {

  val scalatest     = "org.scalatest"       %% "scalatest" % "3.0.7"
  val machinist     = "org.typelevel"       %% "machinist" % "0.6.6"
  val `cats-helper` = "com.evolutiongaming" %% "cats-helper" % "0.0.1"

  object Cats {
    private val version = "1.6.0"
    val core   = "org.typelevel" %% "cats-core" % version
    val kernel = "org.typelevel" %% "cats-kernel" % version
    val macros = "org.typelevel" %% "cats-macros" % version
    val effect = "org.typelevel" %% "cats-effect" % "1.2.0"
  }
}