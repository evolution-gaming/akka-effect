package com.evolutiongaming.akkaeffect.persistence

import akka.actor.ActorRef
import cats.Monad
import cats.effect.Resource
import cats.implicits._
import com.evolutiongaming.akkaeffect._

private[akkaeffect] trait Persistence2[F[_], S, C, E] {

  type Result = F[Option[Releasable[F, Persistence2[F, S, C, E]]]]

  def snapshotOffer(snapshotOffer: SnapshotOffer[S]): Result

  def event(event: E, seqNr: SeqNr): Result

  def recoveryCompleted(seqNr: SeqNr): Result

  def command(cmd: C, seqNr: SeqNr, sender: ActorRef): Result
}

private[akkaeffect] object Persistence2 {

  def preStart[F[_] : Monad, S, C, E](
    persistenceSetup: PersistenceSetup[F, S, C, E]
  ): Resource[F, Option[Persistence2[F, S, C, E]]] = {

    val result: Persistence2[F, S, C, E] = new Persistence2[F, S, C, E] {

      def snapshotOffer(snapshotOffer: SnapshotOffer[S]) = ???

      def event(event: E, seqNr: SeqNr) = ???

      def recoveryCompleted(seqNr: SeqNr) = ???

      def command(cmd: C, seqNr: SeqNr, sender: ActorRef) = ???
    }

    result.some.pure[Resource[F, *]]
  }
}
