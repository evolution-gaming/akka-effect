package com.evolutiongaming.akkaeffect.persistence

import akka.actor.ActorRef
import cats.effect.{Resource, Sync}
import cats.implicits._
import com.evolutiongaming.akkaeffect.Releasable.implicits._
import com.evolutiongaming.akkaeffect._
import com.evolutiongaming.akkaeffect.persistence.Fail.implicits._

// TODO rename
private[akkaeffect] trait Persistence2[F[_], S, C, E, R] {

  type Result = Option[Releasable[F, Persistence2[F, S, C, E, R]]]

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

private[akkaeffect] object Persistence2 {

  def started[F[_] : Sync : Fail, S, C, E, R](
    persistenceSetup: PersistenceSetup[F, S, C, E, R],
  ): Resource[F, Option[Persistence2[F, S, C, E, R]]] = {

    val result: Persistence2[F, S, C, E, R] = new Persistence2[F, S, C, E, R] {

      def snapshotOffer(snapshotOffer: SnapshotOffer[S]) = {
        persistenceSetup
          .recoveryStarted(snapshotOffer.some)
          .flatMap { recovering =>
            Resource
              .liftF(recovering.initial)
              .map { state => Persistence2.recovering(state, recovering) }
          }
          .toReleasable
          .map { _.some }
      }

      def event(event: E, seqNr: SeqNr) = {
        println(s"Persistence2.event $event")
        persistenceSetup
          .recoveryStarted(none)
          .flatMap { recovering =>
            Resource
              .liftF(recovering.initial)
              .flatMap { state =>
                val persistence = recovering
                  .replay(state, event, seqNr)
                  .map { state =>
                    Persistence2.recovering(state, recovering)
                  }
                Resource.liftF(persistence)
              }
          }
          .toReleasable
          .map { _.some }
      }

      def recoveryCompleted(
        seqNr: SeqNr,
        replyOf: ReplyOf[F, R],
        journaller: Journaller[F, E],
        snapshotter: Snapshotter[F, S]
      ) = {
        persistenceSetup
          .recoveryStarted(none)
          .flatMap { recovering =>
            Resource
              .liftF(recovering.initial)
              .flatMap { state =>
                val receive = recovering
                  .recoveryCompleted(state, seqNr, journaller, snapshotter)
                  .map { receive => Persistence2.receive[F, S, C, E, R](replyOf, receive) }
                Resource.liftF(receive)
              }
          }
          .toReleasable
          .map { _.some }
      }

      def command(cmd: C, seqNr: SeqNr, sender: ActorRef) = {
        unexpected[F, Result](name = s"command $cmd", state = "started")
      }
    }

    result.some.pure[Resource[F, *]]
  }


  def recovering[F[_] : Sync : Fail, S, C, E, R](
    state: S,
    recovering: Recovering[F, S, C, E, R]
  ): Persistence2[F, S, C, E, R] = {

    new Persistence2[F, S, C, E, R] {

      def snapshotOffer(snapshotOffer: SnapshotOffer[S]) = {
        unexpected[F, Result](name = s"snapshotOffer $snapshotOffer", state = "receive")
      }

      def event(event: E, seqNr: SeqNr) = {
        println(s"Persistence2.event $event")
        recovering
          .replay(state, event, seqNr)
          .map { state =>
            val persistence = Persistence2.recovering(state, recovering)
            persistence.pure[Releasable[F, *]].some
          }
      }

      def recoveryCompleted(
        seqNr: SeqNr,
        replyOf: ReplyOf[F, R],
        journaller: Journaller[F, E],
        snapshotter: Snapshotter[F, S]
      ) = {
        recovering
          .recoveryCompleted(state, seqNr, journaller, snapshotter)
          .map { receive =>
            val persistence = Persistence2.receive[F, S, C, E, R](replyOf, receive)
            Releasable(persistence).some
          }
      }

      def command(cmd: C, seqNr: SeqNr, sender: ActorRef) = {
        unexpected[F, Result](name = s"command $cmd", state = "recovering")
      }
    }
  }


  def receive[F[_] : Sync : Fail, S, C, E, R](
    replyOf: ReplyOf[F, R],
    receive: Receive[F, C, R]
  ): Persistence2[F, S, C, E, R] = {

    new Persistence2[F, S, C, E, R] { self =>

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
        receive(cmd, reply).map {
          case false => Releasable(self).some
          case true  => none
        }
      }
    }
  }


  private def unexpected[F[_] : Fail, A](name: String, state: String): F[A] = {
    s"$name is not expected in $state".fail[F, A]
  }
}
