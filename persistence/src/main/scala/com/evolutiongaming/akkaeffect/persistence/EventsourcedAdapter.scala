package com.evolutiongaming.akkaeffect.persistence

import akka.persistence._
import cats.data.{NonEmptyList => Nel}
import cats.effect.Async
import cats.implicits._
import com.evolutiongaming.akkaeffect.ActorContextAdapter
import com.evolutiongaming.catshelper.FromFuture

trait EventsourcedAdapter[F[_], A] {

  def journaller: Journaller[F, A]

  def callbacks: EventsourcedAdapter.Callbacks
}

object EventsourcedAdapter {

  def apply[F[_] : Async : FromFuture](
    adapter: ActorContextAdapter[F],
    actor: PersistentActor // TODO narrow type
  ): EventsourcedAdapter[F, Any] = {

    var callbacksVar = Callbacks.empty

    val self = actor.context.self

    new EventsourcedAdapter[F, Any] {

      val journaller = new Journaller[F, Any] {

        def append(events: Nel[Any]) = {

          Async[F].asyncF[SeqNr] { callback =>

            adapter.get {

              callbacksVar = new Callbacks {

                def onPersistFailure(cause: Throwable, event: Any, seqNr: SeqNr) = {
                  val failure = PersistentActorError(s"$self.persistAll failed for $event", cause) // TODO persistenceId in all errors
                  callbacksVar = Callbacks.empty
                  callback(failure.asLeft[SeqNr])
                }

                def onPersistRejected(cause: Throwable, event: Any, seqNr: SeqNr) = {
                  val failure = PersistentActorError(s"$self.persistAll rejected for $event", cause) // TODO persistenceId in all errors
                  callbacksVar = Callbacks.empty
                  callback(failure.asLeft[SeqNr])
                }
              }

              var left = events.size

              actor.persistAll(events.toList) { _ =>
                left = left - 1
                if (left == 0) {
                  callbacksVar = Callbacks.empty
                  callback(actor.lastSequenceNr.asRight[Throwable])
                }
              }
            }
          }
        }
      }

      val callbacks = new Callbacks {

        def onPersistFailure(cause: Throwable, event: Any, seqNr: SeqNr) = {
          callbacksVar.onPersistFailure(cause, event, seqNr)
        }

        def onPersistRejected(cause: Throwable, event: Any, seqNr: SeqNr) = {
          callbacksVar.onPersistRejected(cause, event, seqNr)
        }
      }
    }
  }


  trait Callbacks {

    def onPersistFailure(cause: Throwable, event: Any, seqNr: SeqNr): Unit

    def onPersistRejected(cause: Throwable, event: Any, seqNr: SeqNr): Unit
  }

  object Callbacks {

    val empty: Callbacks = new Callbacks {

      def onPersistFailure(cause: Throwable, event: Any, seqNr: SeqNr) = {}

      def onPersistRejected(cause: Throwable, event: Any, seqNr: SeqNr) = {}
    }
  }
}
