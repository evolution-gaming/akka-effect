package com.evolutiongaming.akkaeffect.persistence

import akka.actor.ActorRef
import cats.effect.concurrent.Ref
import cats.effect.{Resource, Sync}
import cats.implicits._
import com.evolutiongaming.akkaeffect.Fail.implicits._
import com.evolutiongaming.akkaeffect.Releasable.implicits._
import com.evolutiongaming.akkaeffect._
import com.evolutiongaming.catshelper.CatsHelper._


private[akkaeffect] trait Persistence[F[_], S, C, E] {

  type Result = Releasable[F, Persistence[F, S, C, E]]

  def snapshotOffer(seqNr: SeqNr, snapshotOffer: SnapshotOffer[S]): F[Result]

  def event(seqNr: SeqNr, event: E): F[Result]

  def recoveryCompleted(
    seqNr: SeqNr,
    journaller: Journaller[F, E],
    snapshotter: Snapshotter[F, S]
  ): F[Result]

  def command(seqNr: SeqNr, cmd: C, sender: ActorRef): F[Option[Result]]
}

private[akkaeffect] object Persistence {

  def started[F[_]: Sync: Fail, S, C, E](
    eventSourced: EventSourcedAny[F, S, C, E],
  ): Resource[F, Persistence[F, S, C, E]] = {
    eventSourced
      .start
      .map { a => Persistence.started(a) }
  }

  def started[F[_]: Sync: Fail, S, C, E](
    recoveryStarted: RecoveryStartedAny[F, S, C, E],
  ): Persistence[F, S, C, E] = {

    new Persistence[F, S, C, E] {

      def snapshotOffer(seqNr: SeqNr, snapshotOffer: SnapshotOffer[S]) = {
        recoveryStarted(seqNr, snapshotOffer.some)
          .map { recovering => Persistence.recovering(none, recovering) }
          .toReleasable
      }

      def event(seqNr: SeqNr, event: E) = {
        val result = for {
          recovering <- recoveryStarted(seqNr, none)
          replay     <- Allocated.of(recovering.replay)
          _          <- replay.value(seqNr, event).toResource
        } yield {
          Persistence.recovering(replay.some, recovering)
        }
        result.toReleasable
      }

      def recoveryCompleted(
        seqNr: SeqNr,
        journaller: Journaller[F, E],
        snapshotter: Snapshotter[F, S]
      ) = {
        val receive = for {
          recovering <- recoveryStarted(seqNr, none)
          receive    <- recovering.completed(seqNr, journaller, snapshotter)
        } yield {
          Persistence.receive[F, S, C, E](receive)
        }
        receive.toReleasable
      }

      def command(seqNr: SeqNr, cmd: C, sender: ActorRef) = {
        unexpected[F, Option[Result]](name = s"command $cmd", state = "started")
      }
    }
  }


  def recovering[F[_]: Sync: Fail, S, C, E, R](
    replay: Option[Allocated[F, Replay[F, E]]],
    recovering: RecoveringAny[F, S, C, E]
  ): Persistence[F, S, C, E] = {

    new Persistence[F, S, C, E] {

      def snapshotOffer(seqNr: SeqNr, snapshotOffer: SnapshotOffer[S]) = {
        unexpected[F, Result](name = s"snapshotOffer $snapshotOffer", state = "receive")
      }

      def event(seqNr: SeqNr, event: E) = {
        replay match {
          case Some(replay) =>
            replay
              .value(seqNr, event)
              .as {
                Persistence
                  .recovering(replay.some, recovering)
                  .pure[Releasable[F, *]]
              }

          case None =>
            Allocated
              .of(recovering.replay)
              .flatMap { replay =>
                replay
                  .value(seqNr, event)
                  .as { Persistence.recovering(replay.some, recovering) }
                  .toResource
              }
              .toReleasable
        }
      }

      def recoveryCompleted(
        seqNr: SeqNr,
        journaller: Journaller[F, E],
        snapshotter: Snapshotter[F, S]
      ) = {
        replay
          .foldMapM { _.release }
          .toResource
          .productR {
            recovering
              .completed(seqNr, journaller, snapshotter)
              .map { receive => Persistence.receive[F, S, C, E](receive) }
          }
          .toReleasable
      }

      def command(seqNr: SeqNr, cmd: C, sender: ActorRef) = {
        unexpected[F, Option[Result]](name = s"command $cmd", state = "recovering")
      }
    }
  }


  def receive[F[_]: Sync: Fail, S, C, E](
    receive: ReceiveAny[F, C]
  ): Persistence[F, S, C, E] = {

    new Persistence[F, S, C, E] { self =>

      def snapshotOffer(seqNr: SeqNr, snapshotOffer: SnapshotOffer[S]) = {
        unexpected[F, Result](name = s"snapshotOffer $snapshotOffer", state = "receive")
      }

      def event(seqNr: SeqNr, event: E) = {
        unexpected[F, Result](name = s"event $event", state = "receive")
      }

      def recoveryCompleted(
        seqNr: SeqNr,
        journaller: Journaller[F, E],
        snapshotter: Snapshotter[F, S]
      ) = {
        unexpected[F, Result](name = "recoveryCompleted", state = "receive")
      }

      def command(seqNr: SeqNr, cmd: C, sender: ActorRef) = {
        receive(cmd, sender).map {
          case false => Releasable(self).some
          case true  => none
        }
      }
    }
  }


  private def unexpected[F[_]: Fail, A](name: String, state: String): F[A] = {
    s"$name is not expected in $state".fail[F, A]
  }


  final case class Allocated[F[_], A](value: A, release: F[Unit])

  object Allocated {

    def of[F[_]: Sync, A](a: Resource[F, A]): Resource[F, Allocated[F, A]] = {
      Resource.make {
        for {
          ab           <- a.allocated
          (a, release)  = ab
          ref          <- Ref[F].of(release)
        } yield {
          val release = ref.getAndSet(().pure[F]).flatten
          Allocated(a, release)
        }
      } { _.release }
    }
  }
}
