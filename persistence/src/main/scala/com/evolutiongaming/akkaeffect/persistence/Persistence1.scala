package com.evolutiongaming.akkaeffect.persistence

import akka.actor.{ActorContext, ActorRef}
import cats.effect.{Async, Sync}
import cats.effect.concurrent.Ref
import cats.implicits._
import com.evolutiongaming.akkaeffect._
import com.evolutiongaming.catshelper.{ApplicativeThrowable, FromFuture, MonadThrowable, ToFuture}

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}


trait Persistence1[F[_], S, C, E, R] {

  def onPreStart(setup: PersistenceSetup[F, S, C, E]): R

  def onSnapshotOffer(snapshotOffer: SnapshotOffer[S]): R

  def onEvent(event: E, seqNr: SeqNr): R

  def onRecoveryCompleted(seqNr: SeqNr): R

  def onCommand(cmd: C, adapter: ActorContextAdapter[F], seqNr: SeqNr, ref: ActorRef, sender: ActorRef): R

  def onPostStop(seqNr: SeqNr): R
}


// TODO do we need interface ?
trait Router[F[_], S, C, E] extends Persistence1[F, S, C, E, Unit]

object Router {

  def apply[F[_] : Async : ToFuture : FromFuture, S, C, E](
    adapter: ActorContextAdapter[F],
    journaller: Journaller[F, E], // TODO move to later scope
    snapshotter: Snapshotter[F, S]
  ): Router[F, S, C, E] = {

    type P = Phase[F, S, C, E]

    case class State(receive: Phase[F, S, C, E], release: F[Unit])

    var stateVar = none[Future[State]]

    def update(f: Option[Phase[F, S, C, E]] => F[Option[Phase[F, S, C, E]]]): Unit = {
      /*state.update { phase =>
        f(phase)
          .attempt.flatMap {
          case Right(phase) => phase.fold(adapter.stop.as(none[Phase[S, C, E]]))
          case Left(error) => adapter.fail(error).as(none[Phase[S, C, E]])
        }
        for {
          phase <- f(phase).attempt
          phase <- phase.fold(
            error => adapter.fail(error).as(none[Phase[S, C, E]]),
            phase => phase.fold(adapter.stop.as(none[Phase[S, C, E]])) { _.some.pure[F] })
        } yield phase
      }*/
      ???
    }

    def updateSome(f: Phase[F, S, C, E] => F[Option[Phase[F, S, C, E]]]): Unit = {
      update {
        case Some(value) => f(value)
        case phase       => phase.pure[F]
      }
    }

    new Router[F, S, C, E] {

      def onPreStart(setup: PersistenceSetup[F, S, C, E]) = {
        update {
          case Some(_) => PersistentActorError("onPreStart failed: unexpected phase").raiseError[F, Option[Phase[F, S, C, E]]]
          case None    => Phase.receiveRecover(adapter, setup, journaller, snapshotter).some.pure[F]
        }
      }

      def onSnapshotOffer(snapshotOffer: SnapshotOffer[S]) = {
        //            updateSome { _.onSnapshotOffer(snapshotOffer) }
        ???
      }

      def onEvent(event: E, seqNr: SeqNr) = {
        updateSome { _.onEvent(event, seqNr) }
        ???
      }

      def onRecoveryCompleted(seqNr: SeqNr) = {
        updateSome { _.onRecoveryCompleted(seqNr) }
        ???
      }

      def onCommand(cmd: C, adapter: ActorContextAdapter[F], seqNr: SeqNr, ref: ActorRef, sender: ActorRef) = {
        updateSome { _.onCommand(cmd, adapter, seqNr, ref = ref, sender = sender) }
        ???
      }

      def onPostStop(seqNr: SeqNr) = {
        updateSome { _.onPostStop(seqNr) }
      }
    }
  }
}


trait Phase[F[_], S, C, E] extends Persistence1[F, S, C, E, F[Option[Phase[F, S, C, E]]]]

object Phase {

  private def unexpectedIn[F[_] : ApplicativeThrowable, S, C, E](method: String, phase: String) = {
    PersistentActorError(s"$method is not expected in $phase").raiseError[F, Option[Phase[F, S, C, E]]]
  }

  def receiveRecover[F[_] : ApplicativeThrowable : ToFuture : FromFuture, S, C, E](
    adapter: ActorContextAdapter[F],
    setup: PersistenceSetup[F, S, C, E],
    journaller: Journaller[F, E], // TODO move to later scope
    snapshotter: Snapshotter[F, S] // TODO move to later scope
  ): Phase[F, S, C, E] = {

    def unexpected(method: String) = {
      unexpectedIn[F, S, C, E](method = method, phase = "receiveRecover")
    }

    new Phase[F, S, C, E] {

      def onPreStart(setup: PersistenceSetup[F, S, C, E]) = {
        unexpected("onPreStart")
      }

      def onSnapshotOffer(snapshotOffer: SnapshotOffer[S]) = {
        println(s"onSnapshotOffer: $snapshotOffer")
        /*for {
          recovering <- setup.onRecoveryStarted(snapshotOffer.some, journaller, snapshotter)
          phase      <- receiveEvents[S, C, E](recovering, adapter)
        } yield {
          phase.some // TODO some ?
        }*/
        ???
      }

      def onEvent(event: E, seqNr: SeqNr) = {
        println(s"onEvent event: $event, seqNr: $seqNr")
        /*for {
          recovering <- setup.onRecoveryStarted(none, journaller, snapshotter)
          phase      <- receiveEvents[S, C, E](recovering, adapter)
          phase      <- phase.onEvent(event, seqNr)
        } yield phase*/
        ???
      }

      def onRecoveryCompleted(seqNr: SeqNr) = {
        println(s"onRecoveryCompleted: $seqNr")
        //            for {
        //              recovering <- setup.onRecoveryStarted(none, journaller, snapshotter)
        //              receive    <- recovering.onRecoveryCompleted(recovering.initial, seqNr)
        //            } yield {
        //
        //              val phase = receiveCommand[S, C, E](receive, adapter)
        //              phase.some // TODO some ?
        //            }
        ???
      }

      def onCommand(cmd: C, adapter: ActorContextAdapter[F], seqNr: SeqNr, ref: ActorRef, sender: ActorRef) = {
        unexpected("onCommand")
      }

      def onPostStop(seqNr: SeqNr) = {
        println(s"onPostStop seqNr: $seqNr")
        unexpected("onPostStop")
      }
    }
  }


  // TODO add types ?
  def receiveEvents[F[_] : Sync, S, C, E](
    recovering: Recovering[F, S, C, E],
    adapter: ActorContextAdapter[F]
  ): F[Phase[F, S, C, E]] = {

    for {
      stateRef <- Ref[F].of(recovering.initial)
    } yield {

      def unexpected(method: String) = {
        unexpectedIn[F, S, C, E](method = method, phase = "receiveEvents")
      }

      val replay = recovering.replay

      new Phase[F, S, C, E] {
        self =>

        def onPreStart(setup: PersistenceSetup[F, S, C, E]) = {
          unexpected("onPreStart")
        }

        def onSnapshotOffer(snapshotOffer: SnapshotOffer[S]) = {
          unexpected("onSnapshotOffer")
        }

        def onEvent(event: E, seqNr: SeqNr): F[Option[Phase[F, S, C, E]]] = {
          println(s"onEvent event: $event, seqNr: $seqNr")
          for {
            state <- stateRef.get
            state <- replay(state, event, seqNr)
            _ <- stateRef.set(state)
          } yield {
            self.some
          }
        }

        def onRecoveryCompleted(seqNr: SeqNr) = {
          println(s"onRecoveryCompleted: $seqNr")
          for {
            state <- stateRef.get
            receive <- recovering.onRecoveryCompleted(state, seqNr)
          } yield {
            val phase = receiveCommand[F, S, C, E](receive, adapter)
            phase.some // TODO some ?
          }
        }

        def onCommand(cmd: C, adapter: ActorContextAdapter[F], seqNr: SeqNr, ref: ActorRef, sender: ActorRef) = {
          unexpected("onCommand")
        }

        def onPostStop(seqNr: SeqNr) = {
          println(s"onPostStop seqNr: $seqNr")
          unexpected("onPostStop")
        }
      }
    }
  }


  def receiveCommand[F[_] : Sync, S, C, E](
    receive: Receive[F, C, Any],
    adapter: ActorContextAdapter[F]
  ): Phase[F, S, C, E] = {

    def unexpected(method: String) = {
      unexpectedIn[F, S, C, E](method = method, phase = "receiveCommand")
    }

    new Phase[F, S, C, E] {
      self =>

      def onPreStart(setup: PersistenceSetup[F, S, C, E]) = {
        unexpected("onPreStart")
      }

      def onSnapshotOffer(snapshotOffer: SnapshotOffer[S]) = {
        unexpected("onSnapshotOffer")
      }

      def onEvent(event: E, seqNr: SeqNr) = {
        unexpected("onEvent")
      }

      def onRecoveryCompleted(seqNr: SeqNr) = {
        unexpected("onRecoveryCompleted")
      }

      def onCommand(cmd: C, adapter: ActorContextAdapter[F], seqNr: SeqNr, ref: ActorRef, sender: ActorRef) = {
        println(s"onCommand cmd: $cmd, sender: $sender, seqNr: $seqNr")

        val reply = Reply.fromActorRef[F](to = sender, from = Some(ref))
        for {
          stop <- receive(cmd, reply)
          phase <- if (stop) adapter.stop.as(none[Phase[F, S, C, E]]) else self.some.pure[F]
        } yield phase
      }

      def onPostStop(seqNr: SeqNr) = {
        println(s"onPostStop seqNr: $seqNr")
        unexpected("onPostStop")
      }
    }
  }
}
