package com.evolutiongaming.akkaeffect

import akka.actor.ActorSystem
import akka.actor.Props
import akka.pattern.ask
import akka.util.Timeout
import cats.effect.Async
import cats.effect.IO
import cats.effect.Resource
import cats.effect.syntax.all._
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import com.evolutiongaming.catshelper.ToFuture
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._
import scala.concurrent.Await

class ActorOfTest extends AnyFunSuite with Matchers {

  test("ActorCtx effectful methods should always terminate") {
    effecfulMethodsMustTerminate[IO]
  }

  val system = ActorSystem("ActorOfTest")

  implicit val timeout: Timeout = 1.second

  def effecfulMethodsMustTerminate[F[_]: Async: ToFuture] = {

    sealed trait Command
    case class StopAsync(after: FiniteDuration)  extends Command
    case class SlowAction(delay: FiniteDuration) extends Command

    val receiveOf = ReceiveOf[F] { context =>
      val receive = Receive[Envelope[Any]] { envelope =>
        envelope.msg match {

          case StopAsync(after) =>
            val effect = for {
              _ <- Async[F].sleep(after)
              _ <- context.stop
            } yield {}

            effect.start as false

          case SlowAction(delay) =>
            for {
              _ <- Async[F].sleep(delay)
              _ <- context.setReceiveTimeout(1.second)
              _  = envelope.from ! "done"
            } yield false
        }
      } {
        true.pure[F]
      }
      receive.pure[Resource[F, *]]
    }

    val actor = system.actorOf(Props(ActorOf(receiveOf)))
    actor ! StopAsync(100.millis)
    val future = actor ? SlowAction(200.millis)

    Await.result(future, 2.seconds) shouldBe "done"
  }

}
