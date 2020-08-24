package com.evolutiongaming.akkaeffect.persistence

import akka.actor.{ActorContext, ActorRef}
import cats.effect.Sync
import cats.implicits._
import com.evolutiongaming.akkaeffect.{Act, ActorVar, Fail}
import com.evolutiongaming.catshelper.{FromFuture, ToFuture}

import scala.concurrent.Future

private[akkaeffect] trait PersistenceVar[F[_], S, E, C] {

  def preStart(eventSourced: EventSourced[F, S, E, C]): Unit

  def snapshotOffer(seqNr: SeqNr, snapshotOffer: SnapshotOffer[S]): Unit

  def event(seqNr: SeqNr, event: E): Unit

  def recoveryCompleted(
    seqNr: SeqNr,
    journaller: Journaller[F, E],
    snapshotter: Snapshotter[F, S]
  ): Unit

  def command(cmd: C, seqNr: SeqNr, sender: ActorRef): Unit

  def timeout(seqNr: SeqNr): Unit

  def postStop(seqNr: SeqNr): F[Unit]
}

private[akkaeffect] object PersistenceVar {

  def apply[F[_] : Sync : ToFuture : FromFuture : Fail, S, E, C](
    act: Act[Future],
    context: ActorContext
  ): PersistenceVar[F, S, E, C] = {
    apply(ActorVar[F, Persistence[F, S, E, C]](act, context))
  }

  def apply[F[_] : Sync : Fail, S, E, C](
    actorVar: ActorVar[F, Persistence[F, S, E, C]]
  ): PersistenceVar[F, S, E, C] = {

    new PersistenceVar[F, S, E, C] {

      def preStart(eventSourced: EventSourced[F, S, E, C]) = {
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

      def timeout(seqNr: SeqNr) = {
        actorVar.receive { _.timeout(seqNr) }
      }

      def postStop(seqNr: SeqNr) = {
        actorVar.postStop()
      }
    }
  }
}
