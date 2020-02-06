package com.evolutiongaming.akkaeffect.persistence

import akka.actor.Actor
import akka.persistence._
import cats.data.{NonEmptyList => Nel}
import cats.effect.concurrent.{Deferred, Ref}
import cats.effect.{Concurrent, Resource, Sync}
import cats.implicits._
import com.evolutiongaming.akkaeffect.Act
import com.evolutiongaming.catshelper.CatsHelper._
import com.evolutiongaming.catshelper.ToTry


trait Journaller[F[_], -A] {
  /**
    * @see [[akka.persistence.PersistentActor.persistAll]]
    * @return SeqNr of last event
    */
  def append(events: Nel[A]): F[SeqNr]

  /**
    * @see [[akka.persistence.Eventsourced.deleteMessages]]
    * @return Outer F[_] is about deletion in background, inner F[_] is about deletion being completed
    */
  def deleteTo(seqNr: SeqNr): F[F[Unit]]
}


object Journaller {

  private[akkaeffect] def adapter[F[_] : Concurrent : ToTry](
    act: Act,
    actor: PersistentActor)(
    stopped: => Throwable
  ): Resource[F, Adapter[F, Any]] = {

    adapter[F](
      act,
      Eventsourced(actor),
      Sync[F].delay { stopped })
  }

  private[akkaeffect] def adapter[F[_] : Concurrent : ToTry](
    act: Act,
    eventsourced: Eventsourced,
    stopped: F[Throwable]
  ): Resource[F, Adapter[F, Any]] = {

    val deleteMessages = Call.adapter[F, SeqNr, Unit](act, stopped.void) {
      case DeleteMessagesSuccess(a)    => (a, ().pure[F])
      case DeleteMessagesFailure(e, a) => (a, e.raiseError[F, Unit])
    }

    val ref = Resource.make {
        Ref[F].of(none[Deferred[F, F[SeqNr]]])
      } { ref =>
        for {
          deferred <- ref.getAndSet(none)
          result   <- deferred.foldMapM { deferred =>
            for {
              stopped <- stopped
              _       <- deferred.complete(stopped.raiseError[F, SeqNr])
            } yield {}
          }
        } yield result
      }

      for {
        ref            <- ref
        deleteMessages <- deleteMessages
      } yield {
        new Adapter[F, Any] {

          val value = new Journaller[F, Any] {

            def append(events: Nel[Any]) = {
              def persist(deferred: Deferred[F, F[SeqNr]]) = {
                Sync[F].delay {
                  act {
                    val size = events.size
                    val seqNr = eventsourced.lastSequenceNr + size
                    eventsourced.persistAllAsync(events.toList) { _ =>
                      if (eventsourced.lastSequenceNr == seqNr) {
                        ref
                          .set(none)
                          .productR { deferred.complete(seqNr.pure[F]) }
                          .toTry
                          .get
                      }
                    }
                  }
                }
              }

              for {
                deferred <- Deferred[F, F[SeqNr]]
                _        <- ref.set(deferred.some)
                _        <- persist(deferred)
                seqNr    <- deferred.get.flatten
              } yield seqNr
            }

            def deleteTo(seqNr: SeqNr) = {
              deleteMessages
                .value {
                  eventsourced.deleteMessages(seqNr)
                  seqNr
                }
                .map { case (_, a) => a }
            }
          }

          def onError(error: Throwable, event: Any, seqNr: SeqNr) = {
            ref
              .getAndSet(none)
              .flatMap { _.foldMapM { _.complete(error.raiseError[F, SeqNr]) } }
              .toTry
              .get
          }

          def receive = deleteMessages.receive
        }
    }
  }

  private[akkaeffect] trait Adapter[F[_], A] {

    def value: Journaller[F, A]

    def onError(error: Throwable, event: Any, seqNr: SeqNr): Unit

    def receive: Actor.Receive
  }


  private[akkaeffect] trait Eventsourced {

    def lastSequenceNr: SeqNr

    def persistAllAsync[A](events: List[A])(handler: A => Unit): Unit

    def deleteMessages(toSequenceNr: Long): Unit
  }

  private[akkaeffect] object Eventsourced {

    def apply(actor: PersistentActor): Eventsourced = {
      new Eventsourced {

        def lastSequenceNr = actor.lastSequenceNr

        def persistAllAsync[A](events: List[A])(f: A => Unit) = actor.persistAllAsync(events)(f)

        def deleteMessages(toSequenceNr: SeqNr) = actor.deleteMessages(toSequenceNr)
      }
    }
  }
}