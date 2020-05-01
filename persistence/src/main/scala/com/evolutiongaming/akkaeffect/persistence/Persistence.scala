package com.evolutiongaming.akkaeffect.persistence

import akka.actor.ActorRef
import cats.data.OptionT
import cats.effect.concurrent.Ref
import cats.effect.{Resource, Sync}
import cats.implicits._
import com.evolutiongaming.akkaeffect.Fail.implicits._
import com.evolutiongaming.akkaeffect.Releasable.implicits._
import com.evolutiongaming.akkaeffect._
import com.evolutiongaming.catshelper.CatsHelper._


private[akkaeffect] trait Persistence[F[_], S, C, E, R] {

  type Result = Option[Releasable[F, Persistence[F, S, C, E, R]]]

  def snapshotOffer(seqNr: SeqNr, snapshotOffer: SnapshotOffer[S]): F[Result]

  def event(seqNr: SeqNr, event: E): F[Result]

  def recoveryCompleted(
    seqNr: SeqNr,
    replyOf: ReplyOf[F, R],
    journaller: Journaller[F, E],
    snapshotter: Snapshotter[F, S]
  ): F[Result]

  def command(seqNr: SeqNr, cmd: C, sender: ActorRef): F[Result]
}

private[akkaeffect] object Persistence {

  def started[F[_]: Sync: Fail, S, C, E, R](
    eventSourced: EventSourcedAny[F, S, C, E, R],
  ): Resource[F, Option[Persistence[F, S, C, E, R]]] = {
    eventSourced
      .start
      .map { _.map { a => Persistence.started(a) } }
  }

  def started[F[_]: Sync: Fail, S, C, E, R](
    recoveryStarted: RecoveryStartedAny[F, S, C, E, R],
  ): Persistence[F, S, C, E, R] = {

    new Persistence[F, S, C, E, R] {

      def snapshotOffer(seqNr: SeqNr, snapshotOffer: SnapshotOffer[S]) = {
        recoveryStarted(seqNr, snapshotOffer.some)
          .flatMap { recovering =>
            recovering.traverse { recovering =>
              Persistence
                .recovering(none, recovering)
                .pure[Resource[F, *]]
            }
          }
          .toReleasableOpt
      }

      def event(seqNr: SeqNr, event: E) = {
        recoveryStarted(seqNr, none)
          .flatMap { recovering =>
            recovering.traverse { recovering =>
              for {
                replay <- Allocated.of(recovering.replay)
                _      <- replay.value(seqNr, event).toResource
              } yield {
                Persistence.recovering(replay.some, recovering)
              }
            }
          }
          .toReleasableOpt
      }

      def recoveryCompleted(
        seqNr: SeqNr,
        replyOf: ReplyOf[F, R],
        journaller: Journaller[F, E],
        snapshotter: Snapshotter[F, S]
      ) = {

        val receive = for {
          recovering <- OptionT(recoveryStarted(seqNr, none))
          receive    <- OptionT(recovering.completed(seqNr, journaller, snapshotter))
        } yield {
          Persistence.receive[F, S, C, E, R](replyOf, receive)
        }

        receive
          .value
          .toReleasableOpt
      }

      def command(seqNr: SeqNr, cmd: C, sender: ActorRef) = {
        unexpected[F, Result](name = s"command $cmd", state = "started")
      }
    }
  }


  def recovering[F[_]: Sync: Fail, S, C, E, R](
    replay: Option[Allocated[F, Replay1[F, E]]],
    recovering: RecoveringAny[F, S, C, E, R]
  ): Persistence[F, S, C, E, R] = {

    new Persistence[F, S, C, E, R] {

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
                  .some
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
              .map { _.some }
        }
      }

      def recoveryCompleted(
        seqNr: SeqNr,
        replyOf: ReplyOf[F, R],
        journaller: Journaller[F, E],
        snapshotter: Snapshotter[F, S]
      ) = {
        replay
          .foldMapM { _.release }
          .toResource
          .productR {
            recovering
              .completed(seqNr, journaller, snapshotter)
              .map { receive =>
                receive.map { receive => Persistence.receive[F, S, C, E, R](replyOf, receive) }
              }
          }
          .toReleasableOpt
      }

      def command(seqNr: SeqNr, cmd: C, sender: ActorRef) = {
        unexpected[F, Result](name = s"command $cmd", state = "recovering")
      }
    }
  }


  def receive[F[_]: Sync: Fail, S, C, E, R](
    replyOf: ReplyOf[F, R],
    receive: ReceiveAny[F, C]
  ): Persistence[F, S, C, E, R] = {

    new Persistence[F, S, C, E, R] { self =>

      def snapshotOffer(seqNr: SeqNr, snapshotOffer: SnapshotOffer[S]) = {
        unexpected[F, Result](name = s"snapshotOffer $snapshotOffer", state = "receive")
      }

      def event(seqNr: SeqNr, event: E) = {
        unexpected[F, Result](name = s"event $event", state = "receive")
      }

      def recoveryCompleted(
        seqNr: SeqNr,
        replyOf: ReplyOf[F, R],
        journaller: Journaller[F, E],
        snapshotter: Snapshotter[F, S]
      ) = {
        unexpected[F, Result](name = "recoveryCompleted", state = "receive")
      }

      def command(seqNr: SeqNr, cmd: C, sender: ActorRef) = {
        val reply = replyOf(sender) // TODO remove reply
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
