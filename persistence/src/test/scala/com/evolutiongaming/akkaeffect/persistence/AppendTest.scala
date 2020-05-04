package com.evolutiongaming.akkaeffect.persistence

import cats.data.{NonEmptyList => Nel}
import cats.effect.{IO, Sync}
import cats.effect.concurrent.Ref
import cats.implicits._
import com.evolutiongaming.akkaeffect.IOSuite._
import com.evolutiongaming.akkaeffect._
import com.evolutiongaming.catshelper.CatsHelper._
import com.evolutiongaming.catshelper.{FromFuture, ToFuture, ToTry}
import org.scalatest.funsuite.AsyncFunSuite
import org.scalatest.matchers.should.Matchers

import scala.collection.immutable.Queue
import scala.util.control.NoStackTrace

class AppendTest extends AsyncFunSuite with Matchers {

  private implicit val toTry = ToTryFromToFuture.syncOrError[IO]
  
  test("adapter") {
    adapter[IO].run()
  } 
  
  private def adapter[F[_] : Sync : ToFuture : FromFuture : ToTry]: F[Unit] = {

    case class Event(fa: F[Unit])

    def eventsourced(act: Act[F], ref: Ref[F, Queue[F[Unit]]]): F[Append.Eventsourced] = {
      Ref[F]
        .of(0L)
        .map { seqNr =>
          new Append.Eventsourced {

            def lastSequenceNr = seqNr.get.toTry.get

            def persistAllAsync[A](events: List[A])(handler: A => Unit) = {
              val handlers = for {
                _ <- seqNr.update { _ + events.size }
                _ <- events.foldMapM { event => act { handler(event) } }
              } yield {}
              ref
                .update { _.enqueue(handlers) }
                .toTry
                .get
            }
          }
        }
    }

    val error: Throwable = new RuntimeException with NoStackTrace
    val stopped: Throwable = new RuntimeException with NoStackTrace

    for {
      ref          <- Ref[F].of(Queue.empty[F[Unit]])
      act          <- Act.of[F]
      eventsourced <- eventsourced(act, ref)
      result       <- Append
        .adapter[F, Int](act, eventsourced, stopped.pure[F])
        .use { append =>

          def dequeue = {
            ref
              .modify { queue =>
                queue.dequeueOption match {
                  case Some((a, queue)) => (queue, a)
                  case None             => (Queue.empty, ().pure[F])
                }
              }
              .flatten
          }

          for {
            seqNr0 <- append.value(Events.batched(Nel.of(0, 1), Nel.of(2)))
            seqNr1 <- append.value(Events.of(3))
            queue  <- ref.get
            _       = queue.size shouldEqual 3
            _      <- dequeue
            _      <- dequeue
            seqNr  <- seqNr0
            _       = seqNr shouldEqual 3L
            queue  <- ref.get
            _       = queue.size shouldEqual 1
            _      <- dequeue
            seqNr  <- seqNr1
            _       = seqNr shouldEqual 4L
            _      <- dequeue
            seqNr0 <- append.value(Events.of(4))
            seqNr1 <- append.value(Events.of(5))
            queue  <- ref.get
            _       = queue.size shouldEqual 2
            _      <- Sync[F].delay { append.onError(error, 2, 2L) }
            seqNr  <- seqNr0.attempt
            _       = seqNr shouldEqual error.asLeft
            seqNr  <- seqNr1.attempt
            _       = seqNr shouldEqual error.asLeft
            result <- append.value(Events.of(6))
          } yield result
        }
      result   <- result.attempt
      _         = result shouldEqual stopped.asLeft
    } yield {}
  }
}
