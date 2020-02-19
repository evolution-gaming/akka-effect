package com.evolutiongaming.akkaeffect.persistence

import akka.actor.{ActorContext, ActorRef}
import cats.effect.Sync
import com.evolutiongaming.akkaeffect.{Act, ActorVar, ReplyOf}
import com.evolutiongaming.catshelper.{FromFuture, ToFuture}

import scala.concurrent.Future

private[akkaeffect] trait PersistenceVar[F[_], S, C, E, R] {

  def preStart(eventSourced: EventSourced[F, S, C, E, R]): Unit

  def snapshotOffer(snapshotOffer: SnapshotOffer[S]): Unit

  def event(event: E, seqNr: SeqNr): Unit

  def recoveryCompleted(seqNr: SeqNr, replyOf: ReplyOf[F, R]): Unit

  def command(cmd: C, seqNr: SeqNr, sender: ActorRef): Unit

  def postStop(seqNr: SeqNr): F[Unit]
}

private[akkaeffect] object PersistenceVar {

  def apply[F[_] : Sync : ToFuture : FromFuture : Fail, S, C, E, R](
    act: Act[Future],
    context: ActorContext,
    journaller: Journaller[F, E],
    snapshotter: Snapshotter[F, S]
  ): PersistenceVar[F, S, C, E, R] = {
    apply(
      ActorVar[F, Persistence[F, S, C, E, R]](act, context),
      journaller,
      snapshotter)
  }

  def apply[F[_] : Sync : Fail, S, C, E, R](
    actorVar: ActorVar[F, Persistence[F, S, C, E, R]],
    journaller: Journaller[F, E],
    snapshotter: Snapshotter[F, S]
  ): PersistenceVar[F, S, C, E, R] = {

    new PersistenceVar[F, S, C, E, R] {

      def preStart(eventSourced: EventSourced[F, S, C, E, R]) = {
        actorVar.preStart {
          Persistence.started(eventSourced)
        }
      }

      def snapshotOffer(snapshotOffer: SnapshotOffer[S]) = {
        actorVar.receiveUpdate { _.snapshotOffer(snapshotOffer) }
      }

      def event(event: E, seqNr: SeqNr) = {
        actorVar.receiveUpdate { _.event(event, seqNr) }
      }

      def recoveryCompleted(seqNr: SeqNr, replyOf: ReplyOf[F, R]) = {
        actorVar.receiveUpdate { _.recoveryCompleted(seqNr, replyOf, journaller, snapshotter) }
      }

      def command(cmd: C, seqNr: SeqNr, sender: ActorRef) = {
        actorVar.receiveUpdate { _.command(cmd, seqNr, sender) }
      }

      def postStop(seqNr: SeqNr) = {
        actorVar.postStop()
      }
    }
  }
}
