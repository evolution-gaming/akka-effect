package com.evolutiongaming.akkaeffect

import akka.actor.ActorSystem
import cats.effect.concurrent.Ref
import cats.effect.{Concurrent, IO, Sync, Timer}
import cats.implicits._
import com.evolutiongaming.akkaeffect.IOSuite._
import com.evolutiongaming.catshelper.{FromFuture, ToFuture}
import org.scalatest.{AsyncFunSuite, Matchers}

class CounterSpec extends AsyncFunSuite with ActorSuite with Matchers {
  import CounterSpec._

  test("counter actor") {
    testCounterActor[IO](actorSystem).run()
  }

  test("counter rcv") {
    testCounterRcv[IO].run()
  }

  private def testCounterRcv[F[_] : Sync] = {
    for {
      replies <- Ref[F].of(List.empty[Any])
      tell     = new Reply[F, Any] { def apply(msg: Any) = replies.update { msg :: _ } }
      rcv     <- counter(().pure[F])
      inc      = rcv(Msg.Inc, tell)
      expect   = (n: Int) => replies.get.map { replies => replies.headOption shouldEqual Some(n) }
      _       <- inc
      _       <- expect(1)
      _       <- inc
      _       <- expect(2)
      _       <- inc
      _       <- expect(3)
      _       <- rcv(Msg.Stop, tell)
      _       <- expect(3)
    } yield {}
  }

  private def counter[F[_] : Sync](onStop: F[Unit]) = {
    for {
      ref <- Ref[F].of(0)
    } yield {
      new Receive[F, Msg, Any] {

        def apply(msg: Msg, reply: Reply[F, Any]) = {
          msg match {
            case Msg.Inc =>
              for {
                n <- ref.modify { n =>
                  val n1 = n + 1
                  (n1, n1)
                }
                _ <- reply(n)
              } yield false

            case Msg.Stop =>
              for {
                n <- ref.get
                _ <- reply(n)
              } yield true
          }
        }

        def postStop = onStop
      }
    }
  }

  private def testCounterActor[F[_] : Concurrent : ToFuture : FromFuture : Timer](
    actorSystem: ActorSystem
  ) = {
    val probe = Probe.of[F](actorSystem)
    probe.use { probe =>

      def rcv(onStop: F[Unit]) = {
        for {
          rcv <- counter(onStop)
        } yield {
          val rcvAny = rcv.mapA[Any] {
            case msg: Msg => msg.some.pure[F]
            case _        => none[Msg].pure[F]
          }
          rcvAny.some
        }
      }

      val onStop   = probe.tell(PostStop)
      val actorRef = ActorRefF.of[F](actorSystem, _ => rcv(onStop))
      for {

        result   <- actorRef.use { actorRef0 =>
          val ref    = actorRef0.narrow[Msg]
          val tell   = (msg: Msg) => ref.tell(msg, probe.actorRef.some)
          val inc    = tell(Msg.Inc)
          val expect = (n: Int) => {
            for {
              a <- probe.expect
            } yield for {
              a <- a
            } yield {
              a.msg shouldEqual n
            }
          }
          for {
            a <- expect(1)
            _ <- inc
            _ <- a
            a <- expect(2)
            _ <- inc
            _ <- a
            a <- expect(3)
            _ <- inc
            _ <- a
            a <- expect(3)
            _ <- tell(Msg.Stop)
            _ <- a
          } yield {}
        }
      } yield result
    }
  }
}

object CounterSpec {

  sealed trait Msg

  object Msg {
    case object Inc extends Msg
    case object Stop extends Msg
  }

  final case object PostStop
}