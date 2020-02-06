package com.evolutiongaming.akkaeffect.persistence

import akka.persistence._
import cats.data.{NonEmptyList => Nel}
import cats.effect.concurrent.{Deferred, Ref}
import cats.effect.{Concurrent, Resource, Sync}
import cats.implicits._
import com.evolutiongaming.akkaeffect.Act
import com.evolutiongaming.catshelper.CatsHelper._
import com.evolutiongaming.catshelper.ToTry

import scala.collection.immutable.Queue


// TODO make public and rename to append and reuse ?

// TODO write tests to ensure PersistentActor does not write in parallel
private[akkaeffect] trait Persist[F[_], A] {

  // TODO change API to support Nel[Nel[A]]
  def apply(events: Nel[A]): F[F[SeqNr]]
}

private[akkaeffect] object Persist {

  def adapter[F[_] : Concurrent : ToTry, A](
    act: Act,
    eventsourced: Eventsourced,
    stopped: F[Throwable]
  ): Resource[F, Adapter[F, A]] = {

    def fail(ref: Ref[F, Queue[Deferred[F, F[SeqNr]]]], error: F[Throwable]) = {
      for {
        queue <- ref.getAndSet(Queue.empty)
        result <- queue
          .toList
          .toNel
          .foldMapM { queue =>
            for {
              error <- error
              result <- queue.foldMapM { _.complete(error.raiseError[F, SeqNr]) }
            } yield result
          }
      } yield result
    }

    Resource
      .make {
        Ref[F].of(Queue.empty[Deferred[F, F[SeqNr]]])
      } { ref =>
        fail(ref, stopped)
      }
      .map { ref =>

        val persist: Persist[F, A] = {
          events: Nel[A] => {

            val size = events.size

            def persist(deferred: Deferred[F, F[SeqNr]]) = {
              act.ask {
                for {
                  _ <- ref.update { _.enqueue(deferred) }
                  _ <- Sync[F].delay {
                    var left = size
                    val seqNr = eventsourced.lastSequenceNr + size
                    eventsourced.persistAllAsync(events.toList) { _ =>
                      left = left - 1
                      if (left <= 0) {
                        ref
                          .modify { queue =>
                            queue
                              .dequeueOption
                              .fold {
                                (Queue.empty[Deferred[F, F[SeqNr]]], none[Deferred[F, F[SeqNr]]])
                              } { case (deferred, queue) =>
                                (queue, deferred.some)
                              }
                          }
                          .flatMap { _.foldMapM { _.complete(seqNr.pure[F]) } }
                          .toTry
                          .get
                      }
                    }
                  }
                } yield {}
              }
            }

            for {
              deferred <- Deferred[F, F[SeqNr]]
              _        <- persist(deferred).flatten
            } yield {
              deferred.get.flatten
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
