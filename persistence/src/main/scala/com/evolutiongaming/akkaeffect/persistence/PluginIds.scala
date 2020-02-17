package com.evolutiongaming.akkaeffect.persistence

/**
  * @param journal  @see [[akka.persistence.PersistentActor.journalPluginId]]
  * @param snapshot @see [[akka.persistence.PersistentActor.snapshotPluginId]]
  */
final case class PluginIds(
  journal: Option[String] = None,
  snapshot: Option[String] = None)

object PluginIds {
  val default: PluginIds = PluginIds()
}