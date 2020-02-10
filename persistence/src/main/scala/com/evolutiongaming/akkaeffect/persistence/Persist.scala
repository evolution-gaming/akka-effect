package com.evolutiongaming.akkaeffect.persistence

import akka.persistence._
import cats.data.{NonEmptyList => Nel}
import cats.effect.concurrent.Ref
import cats.effect.{Resource, Sync}
import cats.implicits._
import com.evolutiongaming.akkaeffect.{Act, PromiseEffect}
import com.evolutiongaming.catshelper.CatsHelper._
import com.evolutiongaming.catshelper.{FromFuture, ToTry}

import scala.collection.immutable.Queue


// TODO make public and rename to append and reuse ?

// TODO write tests to ensure PersistentActor does not write in parallel
private[akkaeffect] trait Persist[F[_], A] {

  def apply(events: Nel[Nel[A]]): F[(SeqNr, F[Unit])]
}

private[akkaeffect] object Persist {

  def adapter[F[_] : Sync : FromFuture : ToTry, A](
    act: Act,
    actor: PersistentActor,
    stopped: F[Throwable]
  ): Resource[F, Adapter[F, A]] = {
    adapter(act, Eventsourced(actor), stopped)
  }

  def adapter[F[_] : Sync : FromFuture : ToTry, A](
    act: Act,
    eventsourced: Eventsourced,
    stopped: F[Throwable]
  ): Resource[F, Adapter[F, A]] = {

    def fail(ref: Ref[F, Queue[PromiseEffect[F, SeqNr]]], error: F[Throwable]) = {
      for {
        queue  <- ref.getAndSet(Queue.empty)
        result <- queue
          .toList
          .toNel
          .foldMapM { queue =>
            for {
              error  <- error
              result <- queue.foldMapM { _.fail(error) }
            } yield result
          }
      } yield result
    }

    Resource
      .make {
        Ref[F].of(Queue.empty[PromiseEffect[F, SeqNr]])
      } { ref =>
        fail(ref, stopped)
      }
      .map { ref =>

        val persist: Persist[F, A] = {
          events: Nel[Nel[A]] => {

            val size = events.foldLeft(0) { _ + _.size }

            def persist(promise: PromiseEffect[F, SeqNr]) = {

              act.ask {
                ref
                  .update { _.enqueue(promise) }
                  .toTry
                  .get

                var left = size
                val seqNr = eventsourced.lastSequenceNr + size
                events.toList.foreach { events =>
                  eventsourced.persistAllAsync(events.toList) { _ =>
                    left = left - 1
                    if (left <= 0) {
                      // TODO make sure Act is sync in handler and test this
                      ref
                        .modify { queue =>
                          queue
                            .dequeueOption
                            .fold {
                              (Queue.empty[PromiseEffect[F, SeqNr]], none[PromiseEffect[F, SeqNr]])
                            } { case (promise, queue) =>
                              (queue, promise.some)
                            }
                        }
                        .flatMap { _.foldMapM { _.success(seqNr) } }
                        .toTry
                        .get
                    }
                  }
                }
                eventsourced.lastSequenceNr
              }
            }

            for {
              promise <- PromiseEffect[F, SeqNr]
              seqNr   <- persist(promise)
            } yield {
              (seqNr, promise.get.void)
            }
          }
        }

        val onError: OnError[A] = {
          (error: Throwable, _: A, _: SeqNr) =>
            fail(ref, error.pure[F])
              .toTry
              .get
        }

        Adapter(persist, onError)
      }
  }


  trait Eventsourced {

    def lastSequenceNr: SeqNr

    def persistAllAsync[A](events: List[A])(handler: A => Unit): Unit
  }

  object Eventsourced {

    def apply(actor: PersistentActor): Eventsourced = new Eventsourced {

      def lastSequenceNr = actor.lastSequenceNr

      def persistAllAsync[A](events: List[A])(f: A => Unit) = actor.persistAllAsync(events)(f)
    }
  }


  trait OnError[A] {
    def apply(cause: Throwable, event: A, seqNr: SeqNr): Unit
  }


  final case class Adapter[F[_], A](value: Persist[F, A], onError: OnError[A])
}
