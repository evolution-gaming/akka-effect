package com.evolutiongaming.akkaeffect.persistence

import akka.actor.{ActorContext, ActorRef}
import cats.Monad
import com.evolutiongaming.akkaeffect.{Act, ActorVar}
import com.evolutiongaming.catshelper.{BracketThrowable, FromFuture, ToFuture}

private[akkaeffect] trait PersistenceVar[F[_], S, C, E] {

  def preStart(persistenceSetup: PersistenceSetup[F, S, C, E]): Unit

  def snapshotOffer(snapshotOffer: SnapshotOffer[S]): Unit

  def event(event: E, seqNr: SeqNr): Unit

  def recoveryCompleted(seqNr: SeqNr): Unit

  def command(cmd: C, seqNr: SeqNr, sender: ActorRef): Unit

  def postStop(seqNr: SeqNr): F[Unit]
}

private[akkaeffect] object PersistenceVar {

  def apply[F[_] : BracketThrowable : ToFuture : FromFuture, S, C, E](
    act: Act,
    context: ActorContext
  ): PersistenceVar[F, S, C, E] = {
    apply(ActorVar[F, Persistence2[F, S, C, E]](act, context))
  }

  def apply[F[_] : Monad, S, C, E](
    actorVar: ActorVar[F, Persistence2[F, S, C, E]]
  ): PersistenceVar[F, S, C, E] = {

    new PersistenceVar[F, S, C, E] {

      def preStart(persistenceSetup: PersistenceSetup[F, S, C, E]) = {
        actorVar.preStart {
          Persistence2.preStart(persistenceSetup)
        }
      }

      def snapshotOffer(snapshotOffer: SnapshotOffer[S]) = {
        actorVar.receive { _.snapshotOffer(snapshotOffer) }
      }

      def event(event: E, seqNr: SeqNr) = {
        actorVar.receive { _.event(event, seqNr) }
      }

      def recoveryCompleted(seqNr: SeqNr) = {
        actorVar.receive { _.recoveryCompleted(seqNr) }
      }

      def command(cmd: C, seqNr: SeqNr, sender: ActorRef) = {
        actorVar.receive { _.command(cmd, seqNr, sender) }
      }

      def postStop(seqNr: SeqNr) = {
        actorVar.postStop()
      }
    }
  }
}
