package com.evolutiongaming.akkaeffect.persistence

final case class PluginIds(
  journal: Option[String] = None,
  snapshot: Option[String] = None)

object PluginIds {
  val Default: PluginIds = PluginIds()
}