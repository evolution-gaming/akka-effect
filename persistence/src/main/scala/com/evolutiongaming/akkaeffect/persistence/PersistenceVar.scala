package com.evolutiongaming.akkaeffect.persistence

import akka.actor.{ActorContext, ActorRef}
import cats.effect.Sync
import cats.implicits._
import com.evolutiongaming.akkaeffect.{Act, ActorVar, Fail}
import com.evolutiongaming.catshelper.{FromFuture, ToFuture}

import scala.concurrent.Future

private[akkaeffect] trait PersistenceVar[F[_], S, C, E] {

  def preStart(eventSourced: EventSourcedAny[F, S, C, E]): Unit

  def snapshotOffer(seqNr: SeqNr, snapshotOffer: SnapshotOffer[S]): Unit

  def event(seqNr: SeqNr, event: E): Unit

  def recoveryCompleted(
    seqNr: SeqNr,
    journaller: Journaller[F, E],
    snapshotter: Snapshotter[F, S]
  ): Unit

  def command(cmd: C, seqNr: SeqNr, sender: ActorRef): Unit

  def postStop(seqNr: SeqNr): F[Unit]
}

private[akkaeffect] object PersistenceVar {

  def apply[F[_] : Sync : ToFuture : FromFuture : Fail, S, C, E](
    act: Act[Future],
    context: ActorContext
  ): PersistenceVar[F, S, C, E] = {
    apply(ActorVar[F, Persistence[F, S, C, E]](act, context))
  }

  def apply[F[_] : Sync : Fail, S, C, E](
    actorVar: ActorVar[F, Persistence[F, S, C, E]]
  ): PersistenceVar[F, S, C, E] = {

    new PersistenceVar[F, S, C, E] {

      def preStart(eventSourced: EventSourcedAny[F, S, C, E]) = {
        actorVar.preStart {
          Persistence.started(eventSourced)
        }
      }

      def snapshotOffer(seqNr: SeqNr, snapshotOffer: SnapshotOffer[S]) = {
        actorVar.receive { _.snapshotOffer(seqNr, snapshotOffer).map { _.some } }
      }

      def event(seqNr: SeqNr, event: E) = {
        actorVar.receive { _.event(seqNr, event).map { _.some } }
      }

      def recoveryCompleted(
        seqNr: SeqNr,
        journaller: Journaller[F, E],
        snapshotter: Snapshotter[F, S]
      ) = {
        actorVar.receive { _.recoveryCompleted(seqNr, journaller, snapshotter).map { _.some} }
      }

      def command(cmd: C, seqNr: SeqNr, sender: ActorRef) = {
        actorVar.receive { _.command(seqNr, cmd, sender) }
      }

      def postStop(seqNr: SeqNr) = {
        actorVar.postStop()
      }
    }
  }
}
