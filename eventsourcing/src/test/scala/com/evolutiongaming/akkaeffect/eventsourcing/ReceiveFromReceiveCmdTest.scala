package com.evolutiongaming.akkaeffect.eventsourcing

import akka.actor.ActorRef
import cats.data.{NonEmptyList => Nel}
import cats.effect.concurrent.{Deferred, Ref}
import cats.effect.{Concurrent, IO, Timer}
import cats.implicits._
import com.evolutiongaming.akkaeffect.IOSuite._
import com.evolutiongaming.akkaeffect.persistence.{Append, SeqNr}
import com.evolutiongaming.akkaeffect.{Receive, Reply}
import com.evolutiongaming.catshelper.ToFuture
import org.scalatest.funsuite.AsyncFunSuite
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._


class ReceiveFromReceiveCmdTest extends AsyncFunSuite with Matchers {
  import ReceiveFromReceiveCmdTest._

  test("flow") {
    `happy path`[IO].run()
  }

  private def `happy path`[F[_] : Concurrent : Timer : ToFuture]: F[Unit] = {

    type Idx = Int
    type S = List[Idx]
    type C = F[Validate[F, S, E]]
    type E = F[Unit]
    type R = Either[Throwable, SeqNr]

    val append = Ref[F]
      .of(0L)
      .map { ref =>
        new Append[F, E] {

          def apply(events: Nel[Nel[E]]) = {
            val size = events.foldMap { _.size }
            ref
              .modify { a =>
                val b = a + size
                (b, b)
              }
              .map { b =>
                events
                  .traverse { _.sequence }
                  .as(b)
              }
          }
        }
      }


    trait Gates {

      def receive: Gate[F, Unit]

      def validate: Gate[F, S]

      def append: Gate[F, Unit]

      def effect: Gate[F, Either[Throwable, SeqNr]]
    }

    object Gates {

      def apply(idx: Idx, receive: Receive[F, C, R]): F[Gates] = {
        for {
          receiveGate  <- Gate[F, Unit]
          validateGate <- Gate[F, S]
          effectGate   <- Gate[F, Either[Throwable, SeqNr]]
          appendGate   <- Gate[F, Unit]
          validate     = receiveGate.pass(()).as {
            Validate[F, S, E] { (state, _) =>
              validateGate.pass(state).as {
                val effect = Effect[F] { (seqNr: Either[Throwable, SeqNr]) => effectGate.pass(seqNr) }
                val events = Nel.of(Nel.of(appendGate.pass(())))
                val change = Change(idx :: state, events)
                Directive(change.some, effect)
              }
            }
          }
          _ <- receive(validate, Reply.empty)
        } yield new Gates {

          def receive = receiveGate

          def validate = validateGate

          def append = appendGate

          def effect = effectGate
        }
      }
    }


    for {
      append <- append
      result <- ReceiveFromReceiveCmd[F, S, C, E, R](List.empty[Idx], 0L, append, identity).use { receive =>
        for {
          g0 <- Gates(0, receive)
          g1 <- Gates(1, receive)
          g2 <- Gates(2, receive)

          _ <- g0.receive.`catch`
          _ <- g1.receive.`catch`
          _ <- g2.receive.`catch`

          _ <- g0.receive.open
          a <- g0.validate.`catch`
          _  = a shouldEqual List.empty
          _ <- g0.validate.open

          _ <- g0.append.`catch`

          _ <- g2.receive.open
          a <- g2.validate.`catch`
          _  = a shouldEqual List(0)
          _ <- g2.validate.open

          _ <- g1.receive.open
          a <- g1.validate.`catch`
          _  = a shouldEqual List(2, 0)
          _ <- g1.validate.open

          _ <- g1.effect.open
          _ <- g2.effect.open

          _ <- g2.append.open
          _ <- g1.append.open

          _ <- Timer[F].sleep(100.millis)

          _ <- g0.append.open

          a <- g0.effect.`catch`
          _  = a shouldEqual 1.asRight
          _ <- g0.effect.open

          a <- g2.effect.`catch`
          _  = a shouldEqual 2.asRight

          a <- g1.effect.`catch`
          _  = a shouldEqual 3.asRight
        } yield {}
      }
    } yield result
  }
}

object ReceiveFromReceiveCmdTest {

  trait Gate[F[_], A] {

    def pass(a: A): F[Unit]

    def open: F[Unit]

    def `catch`: F[A]
  }

  object Gate {

    def apply[F[_] : Concurrent, A]: F[Gate[F, A]] = {
      for {
        entry <- Deferred[F, A]
        exit  <- Deferred[F, Unit]
      } yield new Gate[F, A] {

        def pass(a: A) = entry.complete(a) *> exit.get

        def open = exit.complete(())

        def `catch` = entry.get
      }
    }
  }
}