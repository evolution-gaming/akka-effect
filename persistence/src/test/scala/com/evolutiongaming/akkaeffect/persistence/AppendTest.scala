package com.evolutiongaming.akkaeffect.persistence

import cats.data.{NonEmptyList => Nel}
import cats.effect.{Async, IO, Ref, Sync}
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import com.evolutiongaming.akkaeffect.IOSuite._
import com.evolutiongaming.akkaeffect._
import com.evolutiongaming.catshelper.CatsHelper._
import com.evolutiongaming.catshelper.{ToFuture, ToTry}
import org.scalatest.funsuite.AsyncFunSuite
import org.scalatest.matchers.should.Matchers

import scala.collection.immutable.Queue
import scala.concurrent.duration._
import scala.util.control.NoStackTrace

class AppendTest extends AsyncFunSuite with Matchers {

  val error: Throwable   = new RuntimeException with NoStackTrace
  val stopped: Throwable = new RuntimeException with NoStackTrace

  test("incorrect persistence order") {

    type Key = String

    def key(events: List[_]): Key = events.mkString("")

    def eventsourced(act: Act[IO], handlers: Ref[IO, Map[Key, IO[Unit]]]): IO[Append.Eventsourced] =
      IO
        .ref(SeqNr.Min)
        .map { seqNr =>
          new Append.Eventsourced {

            override def lastSequenceNr: SeqNr = seqNr.get.toTry.get

            override def persistAllAsync[A](events: List[A])(handler: A => Unit): Unit = {
              val handle = for {
                _ <- seqNr.update { _ + events.size }
                _ <- events.foldMapM { event => act { handler(event) } }
              } yield {}
              handlers
                .update(_.updated(key(events), handle))
                .toTry
                .get
            }

          }
        }

    val io = for {
      act          <- Act.serial[IO]
      handlers     <- IO.ref(Map.empty[Key, IO[Unit]])
      eventsourced <- eventsourced(act, handlers)
      append       <- Append.adapter[IO, Int](act, eventsourced, stopped.pure[IO]).allocated.map(_._1)

      nel00 = Nel.of(0, 1)
      nel01 = Nel.of(2)
      nel10 = Nel.of(3)

      key00 = key(nel00.toList)
      key01 = key(nel01.toList)
      key10 = key(nel10.toList)

      events0 = Events.batched(nel00, nel01)
      events1 = Events.batched(nel10)

      seqNr0 <- append.value(events0)
      seqNr1 <- append.value(events1)

      handler00 <- handlers.get.map(_.get(key00))
      handler01 <- handlers.get.map(_.get(key01))
      handler10 <- handlers.get.map(_.get(key10))

      _ <- handler01.traverse(identity)
      _ = seqNr0.unsafeRunTimed(1.second) shouldBe None
      _ = seqNr1.unsafeRunTimed(1.second) shouldBe None

      _ <- handler10.traverse(identity)
      _ = seqNr0.unsafeRunTimed(1.second) shouldBe None
      _ = seqNr1.unsafeRunTimed(1.second) shouldBe Some(2)

      _ <- handler00.traverse(identity)
      _ = seqNr0.unsafeRunTimed(1.second) shouldBe Some(4)
      _ = seqNr1.unsafeRunTimed(1.second) shouldBe Some(2)
    } yield {}

    io.run()
  }

  test("adapter") {
    val result = for {
      act <- Act.serial[IO]
      _   <- adapter(act)
    } yield {}
    result.run()
  }

  private def adapter[F[_]: Async: ToFuture: ToTry](act: Act[F]): F[Unit] = {

    case class Event(fa: F[Unit])

    def eventsourced(act: Act[F], ref: Ref[F, Queue[F[Unit]]]): F[Append.Eventsourced] = {
      Ref[F]
        .of(SeqNr.Min)
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

    for {
      ref          <- Ref[F].of(Queue.empty[F[Unit]])
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
