package com.evolutiongaming.akkaeffect.persistence

import com.evolutiongaming.sstream

trait EventStore[F[_], A] extends EventStore.Read[F, A] with EventStore.Write[F, A]

object EventStore {

  trait Read[F[_], A] {
    def read(fromSeqNr: SeqNr): F[sstream.Stream[F, Event[A]]]
  }

  trait Write[F[_], A] {
    def append: Append[F, A]
    def deleteTo: DeleteEventsTo[F]
  }

  final case class Event[A](event: A, seqNr: SeqNr)

}
