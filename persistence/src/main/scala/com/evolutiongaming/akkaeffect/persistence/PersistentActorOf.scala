package com.evolutiongaming.akkaeffect.persistence

import akka.actor.ActorRef
import akka.persistence.{PersistentActor, RecoveryCompleted, SnapshotSelectionCriteria}
import akka.{persistence => ap}
import cats.effect.Async
import cats.effect.concurrent.Ref
import cats.implicits._
import com.evolutiongaming.akkaeffect._
import com.evolutiongaming.catshelper.CatsHelper._
import com.evolutiongaming.catshelper.{FromFuture, ToFuture, ToTry}

object PersistentActorOf {

  def apply[F[_] : Async : ToFuture : FromFuture : ToTry](
    persistenceSetupOf: PersistenceSetupOf[F, Any, Any, Any, Any]
  ): PersistentActor = {


    trait Persistence[S, C, E, R] {

      def onPreStart(setup: PersistenceSetup[F, S, C, E]): R

      def onSnapshotOffer(snapshotOffer: SnapshotOffer[S]): R

      def onEvent(event: E, seqNr: SeqNr): R

      def onRecoveryCompleted(seqNr: SeqNr): R

      def onCommand(cmd: C, adapter: ActorContextAdapter[F], seqNr: SeqNr, ref: ActorRef, sender: ActorRef): R

      def onPostStop(seqNr: SeqNr): R
    }


    trait Router[S, C, E] extends Persistence[S, C, E, Unit]

    object Router {

      def apply[S, C, E](
        adapter: ActorContextAdapter[F],
        journaller: Journaller[F, E],
        snapshotter: Snapshotter[F, S]
      ): Router[S, C, E] = {

        val state = StateVar[F].of(none[Phase[S, C, E]])

        def update(f: Option[Phase[S, C, E]] => F[Option[Phase[S, C, E]]]): Unit = {
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

        def updateSome(f: Phase[S, C, E] => F[Option[Phase[S, C, E]]]): Unit = {
          update {
            case Some(value) => f(value)
            case phase       => phase.pure[F]
          }
        }

        new Router[S, C, E] {

          def onPreStart(setup: PersistenceSetup[F, S, C, E]) = {
            update {
              case Some(_) => PersistentActorError("onPreStart failed: unexpected phase").raiseError[F, Option[Phase[S, C, E]]]
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


    trait Phase[S, C, E] extends Persistence[S, C, E, F[Option[Phase[S, C, E]]]]

    object Phase {

      private def unexpectedIn[S, C, E](method: String, phase: String) = {
        PersistentActorError(s"$method is not expected in $phase").raiseError[F, Option[Phase[S, C, E]]]
      }

      def receiveRecover[S, C, E](
        adapter: ActorContextAdapter[F],
        setup: PersistenceSetup[F, S, C, E],
        journaller: Journaller[F, E], // TODO move to later scope
        snapshotter: Snapshotter[F, S] // TODO move to later scope
      ): Phase[S, C, E] = {

        def unexpected(method: String) = {
          unexpectedIn[S, C, E](method = method, phase = "receiveRecover")
        }

        new Phase[S, C, E] {

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
      def receiveEvents[S, C, E](
        recovering: Recovering[F, S, C, E],
        adapter: ActorContextAdapter[F]
      ): F[Phase[S, C, E]] = {

        for {
          stateRef <- Ref[F].of(recovering.initial)
        } yield {

          def unexpected(method: String) = {
            unexpectedIn[S, C, E](method = method, phase = "receiveEvents")
          }

          val replay = recovering.replay

          new Phase[S, C, E] { self =>

            def onPreStart(setup: PersistenceSetup[F, S, C, E]) = {
              unexpected("onPreStart")
            }

            def onSnapshotOffer(snapshotOffer: SnapshotOffer[S]) = {
              unexpected("onSnapshotOffer")
            }

            def onEvent(event: E, seqNr: SeqNr): F[Option[Phase[S, C, E]]] = {
              println(s"onEvent event: $event, seqNr: $seqNr")
              for {
                state <- stateRef.get
                state <- replay(state, event, seqNr)
                _     <- stateRef.set(state)
              } yield {
                self.some
              }
            }

            def onRecoveryCompleted(seqNr: SeqNr) = {
              println(s"onRecoveryCompleted: $seqNr")
              for {
                state   <- stateRef.get
                receive <- recovering.onRecoveryCompleted(state, seqNr)
              } yield {
                val phase = receiveCommand[S, C, E](receive, adapter)
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


      def receiveCommand[S, C, E](
        receive: Receive[F, C, Any],
        adapter: ActorContextAdapter[F]
      ): Phase[S, C, E] = {

        def unexpected(method: String) = {
          unexpectedIn[S, C, E](method = method, phase = "receiveCommand")
        }

        new Phase[S, C, E] { self =>

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
              stop  <- receive(cmd, reply)
              phase <- if (stop) adapter.stop.as(none[Phase[S, C, E]]) else self.some.pure[F]
            } yield phase
          }

          def onPostStop(seqNr: SeqNr) = {
            println(s"onPostStop seqNr: $seqNr")
            unexpected("onPostStop")
          }
        }
      }
    }

    
    new PersistentActor { actor =>

      val adapter = InReceive.Adapter(self)

      val actorContextAdapter = ActorContextAdapter[F](adapter.inReceive, context)

      val eventsourcedAdapter = EventsourcedAdapter[F](actorContextAdapter, actor)

      val snapshotterAdapter = SnapshotterAdapter[F](actorContextAdapter, actor)

      val router = Router[Any, Any, Any](
        actorContextAdapter,
        eventsourcedAdapter.journaller,
        snapshotterAdapter.snapshotter)

      println("new PersistentActor")
      val persistenceSetup = LazyVal {
        println("setup")
        persistenceSetupOf(actorContextAdapter.ctx)
          .handleErrorWith { error =>
            PersistentActorError(s"$self.preStart failed to allocate persistenceSetup with $error", error)
              .raiseError[F, PersistenceSetup[F, Any, Any, Any]]
          }
          .toTry
          .get
      }

      override def preStart(): Unit = {
        println("preStart")
        super.preStart()
        router.onPreStart(persistenceSetup())
      }

      def persistenceId = persistenceSetup().persistenceId

      override def journalPluginId = {
        persistenceSetup().pluginIds.journal getOrElse super.journalPluginId
      }

      override def snapshotPluginId = {
        persistenceSetup().pluginIds.snapshot getOrElse super.snapshotPluginId
      }

      override def recovery = persistenceSetup().recovery

      override protected def onRecoveryFailure(cause: Throwable, event: Option[Any]) = {
        super.onRecoveryFailure(cause, event)
      }

      override protected def onPersistFailure(cause: Throwable, event: Any, seqNr: Long) = {
        eventsourcedAdapter.callbacks.onPersistFailure(cause, event, seqNr)
        super.onPersistFailure(cause, event, seqNr)
      }

      override protected def onPersistRejected(cause: Throwable, event: Any, seqNr: Long) = {
        eventsourcedAdapter.callbacks.onPersistRejected(cause, event, seqNr)
        super.onPersistRejected(cause, event, seqNr)
      }

      override def deleteMessages(toSequenceNr: Long) = super.deleteMessages(toSequenceNr)

      override def loadSnapshot(persistenceId: String, criteria: SnapshotSelectionCriteria, toSequenceNr: Long) = {
        super.loadSnapshot(persistenceId, criteria, toSequenceNr)
      }

      override def saveSnapshot(snapshot: Any) = super.saveSnapshot(snapshot)

      override def deleteSnapshot(sequenceNr: Long) = super.deleteSnapshot(sequenceNr)

      override def deleteSnapshots(criteria: SnapshotSelectionCriteria) = super.deleteSnapshots(criteria)

      def receiveRecover = {
        case ap.SnapshotOffer(m, s) => router.onSnapshotOffer(SnapshotOffer(m, s))
        case RecoveryCompleted      => router.onRecoveryCompleted(lastSeqNr())
        case event                  => router.onEvent(event, lastSeqNr())
      }

      def receiveCommand = {
        val receiveCommand: Receive = {
          case a => router.onCommand(a, actorContextAdapter, lastSeqNr(), ref = self, sender = sender())
        }

        adapter.receive orElse snapshotterAdapter.receive orElse receiveCommand
      }

      override def postStop() = {
        router.onPostStop(lastSeqNr())
        super.postStop()
      }

      private def lastSeqNr() = lastSequenceNr
    }
  }
}
