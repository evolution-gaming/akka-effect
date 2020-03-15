package com.evolutiongaming.akkaeffect.persistence

import akka.actor.Actor
import akka.persistence._
import cats.Monad
import cats.effect.{Concurrent, Resource, Sync, Timer}
import cats.implicits._
import com.evolutiongaming.akkaeffect.Correlate
import com.evolutiongaming.catshelper.{FromFuture, MonadThrowable, ToFuture, ToTry}

import scala.concurrent.duration.FiniteDuration


trait Journaller[F[_], -A] {
  /**
    * @see [[akka.persistence.PersistentActor.persistAllAsync]]
    */
  def append: Append[F, A]

  /**
    * @see [[akka.persistence.Eventsourced.deleteMessages]]
    * @return outer F[_] is about deletion in background, inner F[_] is about deletion being completed
    */
  def deleteTo(seqNr: SeqNr): F[F[Unit]]
}


object Journaller {

  private[akkaeffect] def adapter[F[_] : Concurrent : Timer : ToTry : ToFuture : FromFuture, A](
    append: Append[F, A],
    actor: PersistentActor,
    stopped: F[Throwable],
    timeout: FiniteDuration
  ): Resource[F, Adapter[F, A]] = {

    adapter(
      append,
      Eventsourced(actor),
      stopped,
      timeout)
  }

  private[akkaeffect] def adapter[F[_] : Concurrent : Timer : ToTry : ToFuture : FromFuture, A](
    append: Append[F, A],
    eventsourced: Eventsourced,
    stopped: F[Throwable],
    timeout: FiniteDuration
  ): Resource[F, Adapter[F, A]] = {

    val append1 = append

    Correlate
      .of[F, SeqNr, Unit](stopped.void)
      .map { deleteMessages =>

        val deleteMessages1 = deleteMessages.toUnsafe

        new Adapter[F, A] {

          val value = new Journaller[F, A] {

            val append = append1

            def deleteTo(seqNr: SeqNr) = {
              for {
                _ <- Sync[F].delay { eventsourced.deleteMessages(seqNr) }
                a <- deleteMessages.call(seqNr, timeout)
              } yield a
            }
          }

          val receive: Actor.Receive = {
            case a: DeleteMessagesSuccess => deleteMessages1.callback(a.toSequenceNr, ().asRight); ()
            case a: DeleteMessagesFailure => deleteMessages1.callback(a.toSequenceNr, a.cause.asLeft); ()
          }
        }
      }
  }


  private[akkaeffect] trait Eventsourced {

    def deleteMessages(toSequenceNr: Long): Unit
  }

  private[akkaeffect] object Eventsourced {

    def apply(actor: PersistentActor): Eventsourced = {
      (toSequenceNr: SeqNr) => actor.deleteMessages(toSequenceNr)
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


    def withFail(fail: Fail[F])(implicit F: MonadThrowable[F]): Journaller[F, A] = new Journaller[F, A] {

      val append = self.append.withFail(fail)

      def deleteTo(seqNr: SeqNr) = {
        fail.adapt(s"failed to delete events to $seqNr") {
          self.deleteTo(seqNr)
        }
      }
    }
  }


  trait Adapter[F[_], A] {

    def value: Journaller[F, A]

    def receive: Actor.Receive
  }

  object Adapter {

    implicit class AdapterOps[F[_], A](val self: Adapter[F, A]) extends AnyVal {

      def withFail(fail: Fail[F])(implicit F: MonadThrowable[F]): Adapter[F, A] = new Adapter[F, A] {

        val value = self.value.withFail(fail)

        def receive = self.receive
      }
    }
  }
}