package com.evolutiongaming.akkaeffect.persistence

import akka.persistence._
import cats.Monad
import cats.effect.{Resource, Sync}
import cats.implicits._
import com.evolutiongaming.akkaeffect.{Act, Adapter}
import com.evolutiongaming.catshelper.{FromFuture, ToTry}

import scala.util.Try


trait Journaller[F[_], -A] {
  /**
    * @see [[akka.persistence.PersistentActor.persistAllAsync]]
    */
  def append: Append[F, A]

  /**
    * @see [[akka.persistence.Eventsourced.deleteMessages]]
    * @return Outer F[_] is about deletion in background, inner F[_] is about deletion being completed
    */
  def deleteTo(seqNr: SeqNr): F[F[Unit]]
}


object Journaller {

  private[akkaeffect] def adapter[F[_] : Sync : ToTry : FromFuture, A](
    act: Act[F],
    append: Append[F, A],
    actor: PersistentActor,
    stopped: F[Throwable]
  ): Resource[F, Adapter[Journaller[F, A]]] = {

    adapter(
      act,
      append,
      Eventsourced(actor),
      stopped)
  }

  private[akkaeffect] def adapter[F[_] : Sync : ToTry : FromFuture, A](
    act: Act[F],
    append: Append[F, A],
    eventsourced: Eventsourced,
    stopped: F[Throwable]
  ): Resource[F, Adapter[Journaller[F, A]]] = {

    val append1 = append

    val deleteMessages = Call.adapter[F, SeqNr, Unit](act, stopped.void) {
      case DeleteMessagesSuccess(a)    => (a, ().pure[Try])
      case DeleteMessagesFailure(e, a) => (a, e.raiseError[Try, Unit])
    }

    deleteMessages.map { deleteMessages =>

      val journaller: Journaller[F, A] = new Journaller[F, A] {

        val append = append1

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

    def apply(actor: PersistentActor): Eventsourced = new Eventsourced {

      def lastSequenceNr = actor.lastSequenceNr

      def deleteMessages(toSequenceNr: SeqNr) = actor.deleteMessages(toSequenceNr)
    }
  }


  implicit class JournallerOps[F[_], A](val self: Journaller[F, A]) extends AnyVal {

    def convert[B](f: B => F[A])(implicit F: Monad[F]): Journaller[F, B] = new Journaller[F, B] {

      val append = self.append.convert(f)

      def deleteTo(seqNr: SeqNr) = self.deleteTo(seqNr)
    }


    def narrow[B <: A]: Journaller[F, B] = new Journaller[F, B] {

      val append = self.append.narrow[B]

      def deleteTo(seqNr: SeqNr) = self.deleteTo(seqNr)
    }
  }
}