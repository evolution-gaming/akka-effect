package com.evolutiongaming.akkaeffect.persistence

import akka.actor.ActorRef
import cats.effect.{Resource, Sync}
import cats.implicits._
import com.evolutiongaming.akkaeffect.Releasable.implicits._
import com.evolutiongaming.akkaeffect._
import com.evolutiongaming.akkaeffect.persistence.Fail.implicits._

private[akkaeffect] trait Persistence2[F[_], S, C, E] {

  type Result = Option[Releasable[F, Persistence2[F, S, C, E]]]

  def snapshotOffer(snapshotOffer: SnapshotOffer[S]): F[Result]

  def event(event: E, seqNr: SeqNr): F[Result]

  def recoveryCompleted(seqNr: SeqNr): F[Result]

  def command(cmd: C, seqNr: SeqNr, sender: ActorRef): F[Result]
}

private[akkaeffect] object Persistence2 {

  def started[F[_] : Sync : Fail, S, C, E](
    persistenceSetup: PersistenceSetup[F, S, C, E],
    journaller: Journaller[F, E],
    snapshotter: Snapshotter[F, S]
  ): Resource[F, Option[Persistence2[F, S, C, E]]] = {

    val result: Persistence2[F, S, C, E] = new Persistence2[F, S, C, E] {

      def snapshotOffer(snapshotOffer: SnapshotOffer[S]) = {
        persistenceSetup
          .recoveryStarted(snapshotOffer.some, journaller, snapshotter)
          .toReleasable
          .map { recovering =>
            recovering
              .map { recovering => Persistence2.recovering(recovering.initial, recovering) }
              .some
          }
      }

      def event(event: E, seqNr: SeqNr) = ???

      def recoveryCompleted(seqNr: SeqNr) = ???

      def command(cmd: C, seqNr: SeqNr, sender: ActorRef) = {
        unexpected[F, Result](name = s"command $cmd", state = "started")
      }
    }

    result.some.pure[Resource[F, *]]
  }


  def recovering[F[_] : Sync : Fail, S, C, E](
    state: S,
    recovering: Recovering[F, S, C, E]
  ): Persistence2[F, S, C, E] = {

    new Persistence2[F, S, C, E] {

      def snapshotOffer(snapshotOffer: SnapshotOffer[S]) = {
        unexpected[F, Result](name = s"snapshotOffer $snapshotOffer", state = "receive")
      }

      def event(event: E, seqNr: SeqNr) = {
        recovering
          .replay(state, event, seqNr)
          .map { state =>
            val persistence = Persistence2.recovering(state, recovering)
            Releasable(persistence).some
          }
      }

      def recoveryCompleted(seqNr: SeqNr) = {
        ???
      }

      def command(cmd: C, seqNr: SeqNr, sender: ActorRef) = {
        unexpected[F, Result](name = s"command $cmd", state = "recovering")
      }
    }
  }


  def receive[F[_] : Sync : Fail, S, C, E, R](
    replyOf: ReplyOf[F, R],
    receive: Receive[F, C, R]
  ): Resource[F, Option[Persistence2[F, S, C, E]]] = {

    val result: Persistence2[F, S, C, E] = new Persistence2[F, S, C, E] { self =>

      def snapshotOffer(snapshotOffer: SnapshotOffer[S]) = {
        unexpected[F, Result](name = s"snapshotOffer $snapshotOffer", state = "receive")
      }

      def event(event: E, seqNr: SeqNr) = {
        unexpected[F, Result](name = s"event $event", state = "receive")
      }

      def recoveryCompleted(seqNr: SeqNr) = {
        unexpected[F, Result](name = "recoveryCompleted", state = "receive")
      }

      def command(cmd: C, seqNr: SeqNr, sender: ActorRef) = {
        val reply = replyOf(sender.some)
        receive(cmd, reply).map {
          case false => Releasable(self).some
          case true  => none
        }
      }
    }

    result.some.pure[Resource[F, *]]
  }


  private def unexpected[F[_] : Fail, A](name: String, state: String): F[A] = {
    s"$name is not expected in $state".fail[F, A]
  }
}
