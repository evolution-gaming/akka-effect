package com.evolutiongaming.akkaeffect.persistence

import com.evolutiongaming.sstream

trait EventStore[F[_], A] extends EventStore.Read[F, A] with EventStore.Write[F, A]

object EventStore {

  trait Read[F[_], A] {
    def events(fromSeqNr: SeqNr): F[sstream.Stream[F, Persisted[A]]]
  }

  trait Write[F[_], A] {
    def save(events: Events[Event[A]]): F[F[SeqNr]]
    def deleteTo(seqNr: SeqNr): F[F[Unit]]
  }

  sealed trait Persisted[+A]
  final case class HighestSeqNr(seqNr: SeqNr)       extends Persisted[Nothing]
  final case class Event[A](event: A, seqNr: SeqNr) extends Persisted[A]

}
