package com.evolutiongaming.akkaeffect.persistence

import akka.persistence._
import cats.effect.kernel.Async
import cats.effect.{Deferred, Resource, Sync}
import cats.implicits._
import com.evolutiongaming.akkaeffect.util.AtomicRef
import com.evolutiongaming.akkaeffect.{Act, Fail}
import com.evolutiongaming.catshelper.CatsHelper._
import com.evolutiongaming.catshelper.{MonadThrowable, ToFuture}

import scala.collection.immutable.Queue

object AppendOf {

  /**
    * @see [[akka.persistence.PersistentActor.persistAllAsync]]
    */
  private[akkaeffect] def adapter[F[_]: Async: ToFuture, A](
    act: Act[F],
    actor: PersistentActor,
    stopped: F[Throwable]
  ): Resource[F, Adapter[F, A]] = {
    adapter(act, Eventsourced(actor), stopped)
  }

  private[akkaeffect] def adapter[F[_]: Async: ToFuture, A](
    act: Act[F],
    eventsourced: Eventsourced,
    stopped: F[Throwable]
  ): Resource[F, Adapter[F, A]] = {

    class Main

    def fail(ref: AtomicRef[Queue[Deferred[F, Either[Throwable, SeqNr]]]], error: F[Throwable]) = {
      ref
        .getAndSet(Queue.empty)
        .toList
        .toNel
        .foldMapM { queue =>
          for {
            error <- error
            result <- queue.foldMapM { _.complete(error.asLeft).void }
          } yield result
        }
    }

    Resource
      .make {
        Sync[F].delay { AtomicRef(Queue.empty[Deferred[F, Either[Throwable, SeqNr]]]) }
      } { ref =>
        Sync[F].defer { fail(ref, stopped) }
      }
      .map { ref =>

        new Main with Adapter[F, A] {

          val value = new Main with Append[F, A] {

            def apply(events: Events[A]) = {
              val size = events.size
              val eventsList = events.values.toList
              for {
                deferred <- Deferred[F, Either[Throwable, SeqNr]]
                _ <- act {
                  ref.update { _.enqueue(deferred) }
                  var left = size
                  eventsList.foreach { events =>
                    eventsourced.persistAllAsync(events.toList) { _ =>
                      left = left - 1
                      if (left <= 0) {
                        val seqNr = eventsourced.lastSequenceNr
                        ref
                          .modify { queue =>
                            queue.dequeueOption match {
                              case Some((promise, queue)) => (queue, promise.some)
                              case None                   => (Queue.empty, none)
                            }
                          }
                          .foreach { deferred =>
                            deferred
                              .complete(seqNr.asRight)
                              .toFuture
                          }
                      }
                    }
                  }
                }
              } yield {
                deferred
                  .get
                  .rethrow
              }
            }
          }

          val onError: OnError[A] = {
            (error: Throwable, _: A, _: SeqNr) =>
              fail(ref, error.pure[F]).toFuture
              ()
          }
        }
      }
  }

  private[akkaeffect] trait Eventsourced {

    def lastSequenceNr: SeqNr

    def persistAllAsync[A](events: List[A])(handler: A => Unit): Unit
  }

  private[akkaeffect] object Eventsourced {

    def apply(actor: PersistentActor): Eventsourced = new Eventsourced {

      def lastSequenceNr = actor.lastSequenceNr

      def persistAllAsync[A](events: List[A])(f: A => Unit) = actor.persistAllAsync(events)(f)
    }
  }


  private[akkaeffect] trait OnError[A] {

    def apply(cause: Throwable, event: A, seqNr: SeqNr): Unit
  }

  private[akkaeffect] trait Adapter[F[_], A] {

    def value: Append[F, A]

    def onError: OnError[A]
  }

  object Adapter {

    implicit class AdapterOps[F[_], A](val self: Adapter[F, A]) extends AnyVal {

      def withFail(fail: Fail[F])(implicit F: MonadThrowable[F]): Adapter[F, A] = new Adapter[F, A] {

        val value = self.value.withFail(fail)

        def onError = self.onError
      }
    }
  }


}
