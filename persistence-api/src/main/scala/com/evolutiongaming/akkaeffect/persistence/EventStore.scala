package com.evolutiongaming.akkaeffect.persistence

import com.evolutiongaming.sstream

trait EventStore[F[_], A] extends EventStore.Read[F, A] with EventStore.Write[F, A]

object EventStore {

  trait Read[F[_], A] {
    def from(seqNr: SeqNr): F[sstream.Stream[F, Event[A]]]
  }

  trait Write[F[_], A] {
    def save(events: Events[A]): F[F[SeqNr]]
    def deleteTo(seqNr: SeqNr): F[F[Unit]]
  }

  final case class Event[A](event: A, seqNr: SeqNr)

}
