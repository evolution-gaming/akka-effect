package com.evolutiongaming.akkaeffect.persistence

import akka.actor.ActorRef
import cats.data.OptionT
import cats.effect.concurrent.Ref
import cats.effect.{Resource, Sync}
import cats.implicits._
import com.evolutiongaming.akkaeffect.Releasable.implicits._
import com.evolutiongaming.akkaeffect._
import com.evolutiongaming.akkaeffect.persistence.Fail.implicits._

private[akkaeffect] trait Persistence[F[_], S, C, E, R] {

  type Result = Option[Releasable[F, Persistence[F, S, C, E, R]]]

  def snapshotOffer(snapshotOffer: SnapshotOffer[S]): F[Result]

  def event(event: E, seqNr: SeqNr): F[Result]

  def recoveryCompleted(
    seqNr: SeqNr,
    replyOf: ReplyOf[F, R],
    journaller: Journaller[F, E],
    snapshotter: Snapshotter[F, S]
  ): F[Result]

  def command(cmd: C, seqNr: SeqNr, sender: ActorRef): F[Result]
}

private[akkaeffect] object Persistence {

  def started[F[_] : Sync : Fail, S, C, E, R](
    eventSourced: EventSourced[F, S, C, E, R],
  ): Resource[F, Option[Persistence[F, S, C, E, R]]] = {
    eventSourced
      .start
      .map { _.map { started => Persistence.started(started) } }
  }

  def started[F[_] : Sync : Fail, S, C, E, R](
    started: Started[F, S, C, E, R],
  ): Persistence[F, S, C, E, R] = {

    new Persistence[F, S, C, E, R] {

      def snapshotOffer(snapshotOffer: SnapshotOffer[S]) = {
        started
          .recoveryStarted(snapshotOffer.some)
          .flatMap { recovering =>
            recovering.traverse { recovering =>
              Resource
                .liftF(recovering.initial)
                .map { state => Persistence.recovering(state, none, recovering) }
            }
          }
          .toReleasableOpt
      }

      def event(event: E, seqNr: SeqNr) = {
        started
          .recoveryStarted(none)
          .flatMap { recovering =>
            recovering.traverse { recovering =>
              for {
                state  <- Resource.liftF(recovering.initial)
                replay <- Allocated.of(recovering.replay)
                state  <- Resource.liftF(replay.value(state, event, seqNr))
              } yield {
                Persistence.recovering(state, replay.some, recovering)
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
          recovering <- OptionT(started.recoveryStarted(none))
          state      <- OptionT.liftF(Resource.liftF(recovering.initial))
          receive    <- OptionT(recovering.recoveryCompleted(state, seqNr, journaller, snapshotter))
        } yield {
          Persistence.receive[F, S, C, E, R](replyOf, receive)
        }

        receive
          .value
          .toReleasableOpt
      }

      def command(cmd: C, seqNr: SeqNr, sender: ActorRef) = {
        unexpected[F, Result](name = s"command $cmd", state = "started")
      }
    }
  }


  def recovering[F[_] : Sync : Fail, S, C, E, R](
    state: S,
    replay: Option[Allocated[F, Replay[F, S, E]]],
    recovering: Recovering[F, S, C, E, R]
  ): Persistence[F, S, C, E, R] = {

    new Persistence[F, S, C, E, R] {

      def snapshotOffer(snapshotOffer: SnapshotOffer[S]) = {
        unexpected[F, Result](name = s"snapshotOffer $snapshotOffer", state = "receive")
      }

      def event(event: E, seqNr: SeqNr) = {
        replay match {
          case Some(replay) =>
            replay
              .value(state, event, seqNr)
              .map { state =>
                Persistence
                  .recovering(state, replay.some, recovering)
                  .pure[Releasable[F, *]]
                  .some
              }

          case None =>
            val result = for {
              replay <- Allocated.of(recovering.replay)
              state  <- Resource.liftF(replay.value(state, event, seqNr))
            } yield {
              Persistence.recovering(state, replay.some, recovering)
            }
            result
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
        Resource
          .liftF(replay.foldMapM { _.release })
          .flatMap { _ =>
            recovering
              .recoveryCompleted(state, seqNr, journaller, snapshotter)
              .map { _.map { receive => Persistence.receive[F, S, C, E, R](replyOf, receive) } }
          }
          .toReleasableOpt
      }

      def command(cmd: C, seqNr: SeqNr, sender: ActorRef) = {
        unexpected[F, Result](name = s"command $cmd", state = "recovering")
      }
    }
  }


  def receive[F[_] : Sync : Fail, S, C, E, R](
    replyOf: ReplyOf[F, R],
    receive: Receive[F, C, R]
  ): Persistence[F, S, C, E, R] = {

    new Persistence[F, S, C, E, R] { self =>

      def snapshotOffer(snapshotOffer: SnapshotOffer[S]) = {
        unexpected[F, Result](name = s"snapshotOffer $snapshotOffer", state = "receive")
      }

      def event(event: E, seqNr: SeqNr) = {
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

      def command(cmd: C, seqNr: SeqNr, sender: ActorRef) = {
        val reply = replyOf(sender)
        receive(cmd, reply, sender).map {
          case false => Releasable(self).some
          case true  => none
        }
      }
    }
  }


  private def unexpected[F[_] : Fail, A](name: String, state: String): F[A] = {
    s"$name is not expected in $state".fail[F, A]
  }


  final case class Allocated[F[_], A](value: A, release: F[Unit])

  object Allocated {

    def of[F[_] : Sync, A](a: Resource[F, A]): Resource[F, Allocated[F, A]] = {
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
