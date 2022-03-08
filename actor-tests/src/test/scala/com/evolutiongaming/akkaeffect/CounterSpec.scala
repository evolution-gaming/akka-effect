package com.evolutiongaming.akkaeffect

import akka.actor.ActorSystem
import cats.effect.unsafe.implicits.global
import cats.effect._
import cats.syntax.all._
import com.evolutiongaming.akkaeffect.IOSuite._
import com.evolutiongaming.akkaeffect.testkit.Probe
import com.evolutiongaming.catshelper.{FromFuture, ToFuture}
import org.scalatest.funsuite.AsyncFunSuite
import org.scalatest.matchers.should.Matchers

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
      reply    = Reply[F, Any] { msg => replies.update { msg :: _ } }
      rcv     <- counter[F]
      inc      = rcv(Call(Msg.Inc, reply))
      expect   = (n: Int) => replies.get.map { replies => replies.headOption shouldEqual Some(n) }
      _       <- inc
      _       <- expect(1)
      _       <- inc
      _       <- expect(2)
      _       <- inc
      _       <- expect(3)
      _       <- rcv(Call(Msg.Stop, reply))
      _       <- expect(3)
    } yield {}
  }

  private def counter[F[_]: Sync] = {
    Ref[F]
      .of(0)
      .map { ref =>
        Receive[Call[F, Msg, Int]] { call =>
          call.msg match {
            case Msg.Inc =>
              for {
                n <- ref.modify { n =>
                  val n1 = n + 1
                  (n1, n1)
                }
                _ <- call.reply(n)
              } yield false

            case Msg.Stop =>
              for {
                n <- ref.get
                _ <- call.reply(n)
              } yield true
          }
        } {
          false.pure[F]
        }
      }
  }

  private def testCounterActor[F[_]: Async: ToFuture: FromFuture](actorSystem: ActorSystem) = {

    val actorRefOf = ActorRefOf.fromActorRefFactory[F](actorSystem)

    val probe = Probe.of[F](actorRefOf)
    probe.use { probe =>

      def receive(onStop: F[Unit]) = {
        Resource
          .make { counter[F] } { _ => onStop }
          .map { receive =>
            Receive[Call[F, Any, Any]] { call =>
              call.msg match {
                case a: Msg => receive(call.copy(msg = a))
                case _      => false.pure[F]
              }
            } {
              false.pure[F]
            }
          }
      }

      val onStop   = probe.actorEffect.tell(PostStop)
      val actorRef = ActorEffect.of[F](actorRefOf, _ => receive(onStop))
      for {

        result   <- actorRef.use { actorRef0 =>
          val ref    = actorRef0.narrow[Msg, Any](_.pure[F])
          val tell   = (msg: Msg) => ref.tell(msg, probe.actorEffect.toUnsafe.some)
          val inc    = tell(Msg.Inc)
          val expect = (n: Int) => {
            for {
              a <- probe.expect[Int]
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