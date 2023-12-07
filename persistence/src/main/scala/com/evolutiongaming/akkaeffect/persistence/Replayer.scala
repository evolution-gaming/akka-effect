package com.evolutiongaming.akkaeffect.persistence

import com.evolutiongaming.sstream.Stream

trait Replayer[F[_], E] {

  def replay(fromSequenceNr: SeqNr, toSequenceNr: SeqNr, max: Long): F[Stream[F, Event[E]]]

}

object Replayer {

  trait Of[F[_]] {

    def apply[E](journalPluginId: String, eventSourcedId: EventSourcedId): F[Replayer[F, E]]

  }

}
