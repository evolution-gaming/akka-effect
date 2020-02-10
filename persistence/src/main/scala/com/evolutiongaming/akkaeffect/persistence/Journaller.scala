package com.evolutiongaming.akkaeffect.persistence

import akka.persistence._
import cats.data.{NonEmptyList => Nel}
import cats.effect.{Resource, Sync}
import cats.implicits._
import com.evolutiongaming.akkaeffect.{Act, Adapter}
import com.evolutiongaming.catshelper.{FromFuture, ToTry}

import scala.util.Try


trait Journaller[F[_], -A] {
  /**
    * @see [[akka.persistence.PersistentActor.persistAllAsync]]
    * @param events to be saved, inner Nel[A] will be persisted atomically, outer Nel[_] for batching
    * @return SeqNr of last event
    */
  def append(events: Nel[Nel[A]]): F[(SeqNr, F[Unit])]

  /**
    * @see [[akka.persistence.Eventsourced.deleteMessages]]
    * @return Outer F[_] is about deletion in background, inner F[_] is about deletion being completed
    */
  def deleteTo(seqNr: SeqNr): F[F[Unit]]
}


object Journaller {

  private[akkaeffect] def adapter[F[_] : Sync : ToTry : FromFuture, A](
    act: Act,
    persist: Persist[F, A],
    actor: PersistentActor,
    stopped: F[Throwable]
  ): Resource[F, Adapter[Journaller[F, A]]] = {

    adapter(
      act,
      persist,
      Eventsourced(actor),
      stopped)
  }

  private[akkaeffect] def adapter[F[_] : Sync : ToTry : FromFuture, A](
    act: Act,
    persist: Persist[F, A],
    eventsourced: Eventsourced,
    stopped: F[Throwable]
  ): Resource[F, Adapter[Journaller[F, A]]] = {

    val deleteMessages = Call.adapter[F, SeqNr, Unit](act, stopped.void) {
      case DeleteMessagesSuccess(a)    => (a, ().pure[Try])
      case DeleteMessagesFailure(e, a) => (a, e.raiseError[Try, Unit])
    }

    deleteMessages.map { deleteMessages =>

      val journaller = new Journaller[F, A] {

        def append(events: Nel[Nel[A]]) = persist(events)

        def deleteTo(seqNr: SeqNr) = {
          deleteMessages
            .value {
              eventsourced.deleteMessages(seqNr)
              seqNr
            }
            .map { case (_, a) => a }
        }
      }

      Adapter(journaller, deleteMessages.receive)
    }
  }


  private[akkaeffect] trait Eventsourced {

    def lastSequenceNr: SeqNr

    def deleteMessages(toSequenceNr: Long): Unit
  }

  private[akkaeffect] object Eventsourced {

    def apply(actor: PersistentActor): Eventsourced = {
      new Eventsourced {

        def lastSequenceNr = actor.lastSequenceNr

        def deleteMessages(toSequenceNr: SeqNr) = actor.deleteMessages(toSequenceNr)
      }
    }
  }
}