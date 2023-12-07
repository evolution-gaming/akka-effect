package com.evolutiongaming.akkaeffect.persistence

trait JournallerOf[F[_]] {

  def apply[E](journalPluginId: String, eventSourcedId: EventSourcedId, currentSeqNr: SeqNr): F[Journaller[F, E]]

}
