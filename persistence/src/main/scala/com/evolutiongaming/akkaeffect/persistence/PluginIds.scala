package com.evolutiongaming.akkaeffect.persistence

import cats.Show
import cats.syntax.all.*

/** @param journal
  *   \@see [[akka.persistence.PersistentActor.journalPluginId]]
  * @param snapshot
  *   \@see [[akka.persistence.PersistentActor.snapshotPluginId]]
  */
final case class PluginIds(journal: Option[String] = None, snapshot: Option[String] = None)

object PluginIds {

  implicit val showPluginIds: Show[PluginIds] = Show.fromToString

  val Empty: PluginIds = PluginIds()

  def apply(journal: String, snapshot: String): PluginIds =
    PluginIds(journal = journal.some, snapshot = snapshot.some)

  implicit class PluginIdsOps(val self: PluginIds) extends AnyVal {

    def orElse(pluginIds: => PluginIds): PluginIds =
      self match {
        case PluginIds(None, None)       => pluginIds
        case PluginIds(Some(_), Some(_)) => self
        case _ =>
          val pluginIds1 = pluginIds
          PluginIds(
            journal = self.journal orElse pluginIds1.journal,
            snapshot = self.snapshot orElse pluginIds1.snapshot,
          )
      }
  }
}
