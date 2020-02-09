package com.evolutiongaming.akkaeffect.persistence

import cats.data.{NonEmptyList => Nel}
import cats.effect.IO
import cats.effect.concurrent.Ref
import cats.implicits._
import com.evolutiongaming.akkaeffect.IOSuite._
import com.evolutiongaming.akkaeffect._
import com.evolutiongaming.catshelper.CatsHelper._
import org.scalatest.funsuite.AsyncFunSuite
import org.scalatest.matchers.should.Matchers

import scala.collection.immutable.Queue
import scala.util.control.NoStackTrace

class PersistTest extends AsyncFunSuite with Matchers {

  test("adapter") {

    // TODO use everywhere
    implicit val toTry = ToTryFromToFuture.syncOrError[IO]

    case class Event(fa: IO[Unit])

    def eventsourced(act: Act, ref: Ref[IO, Queue[IO[Unit]]]): IO[Persist.Eventsourced] = {
      Ref[IO]
        .of(0L)
        .map { seqNr =>
          new Persist.Eventsourced {

            def lastSequenceNr = seqNr.get.toTry.get

            def persistAllAsync[A](events: List[A])(handler: A => Unit) = {
              val handlers = events.foldMapM { event =>
                act
                  .ask { IO { handler(event) } }
                  .flatten
              }
              val result = for {
                _ <- seqNr.update { _ + events.size }
                _ <- ref.update { _.enqueue(handlers) }
              } yield {}
              result
                .toTry
                .get
            }
          }
        }
    }

    val error = new Throwable with NoStackTrace
    val stopped = new Throwable with NoStackTrace

    val result = for {
      ref          <- Ref[IO].of(Queue.empty[IO[Unit]])
      act          <- Act.of[IO]
      eventsourced <- eventsourced(act, ref)
      seqNr        <- Persist
        .adapter[IO, Int](act, eventsourced, stopped.pure[IO])
        .use { persist =>

          def dequeue = {
            ref
              .modify { queue =>
                queue.dequeueOption match {
                  case Some((a, queue)) => (queue, a)
                  case None             => (Queue.empty, ().pure[IO])
                }
              }
              .flatten
          }

          println("1")
          for {
            seqNr0 <- persist.value(Nel.of(Nel.of(0, 1), Nel.of(2)))
            _ = println("2")
            seqNr1 <- persist.value(Nel.of(Nel.of(3)))
            _ = println("3")
            queue  <- ref.get
            _ = println("4")
            _       = queue.size shouldEqual 3
            _      <- dequeue
            _ = println("5")
            _      <- dequeue
            _ = println("6")
            seqNr  <- seqNr0
            _ = println("7")
            _       = seqNr shouldEqual 3L
            queue  <- ref.get
            _       = queue.size shouldEqual 1
            _      <- dequeue
            seqNr  <- seqNr1
            _       = seqNr shouldEqual 4L
            _      <- dequeue
            seqNr0 <- persist.value(Nel.of(Nel.of(3)))
            seqNr1 <- persist.value(Nel.of(Nel.of(4)))
            queue  <- ref.get
            _       = queue.size shouldEqual 2
            _      <- IO { persist.onError(error, 2, 2L) }
            seqNr  <- seqNr0.attempt
            _       = seqNr shouldEqual error.asLeft
            seqNr  <- seqNr1.attempt
            _       = seqNr shouldEqual error.asLeft
            seqNr  <- persist.value(Nel.of(Nel.of(4)))
          } yield seqNr
        }
      seqNr        <- seqNr.attempt
      _             = seqNr shouldEqual stopped.asLeft
    } yield {}

    result.run()
  }
}
