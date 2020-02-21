package com.evolutiongaming.akkaeffect

import akka.actor.{ActorSystem, Status}
import akka.testkit.TestActors
import cats.arrow.FunctionK
import cats.effect.{Concurrent, IO, Sync}
import cats.implicits._
import com.evolutiongaming.akkaeffect.IOSuite._
import com.evolutiongaming.catshelper.{FromFuture, ToFuture}
import org.scalatest.funsuite.AsyncFunSuite
import org.scalatest.matchers.should.Matchers

import scala.util.control.NoStackTrace

class ReplyStatusTest extends AsyncFunSuite with ActorSuite with Matchers {

  test("toString") {
    `toString`[IO](actorSystem).run()
  }

  test("fromActorRef") {
    `fromActorRef`[IO](actorSystem).run()
  }

  private def `toString`[F[_] : Concurrent](actorSystem: ActorSystem) = {

    val actorRefOf = ActorRefOf[F](actorSystem)
    val actorRef = actorRefOf(TestActors.blackholeProps)
    (actorRef, actorRef).tupled.use { case (to, from) =>
      val reply = ReplyStatus.fromActorRef[F](to, from.some)
      Sync[F].delay {
        reply.toString shouldEqual s"Reply(${ to.path }, ${ from.path })"
      }
    }
  }

  private def `fromActorRef`[F[_] : Concurrent : ToFuture : FromFuture](actorSystem: ActorSystem) = {
    val actorRefOf = ActorRefOf[F](actorSystem)
    val resources = for {
      probe <- Probe.of[F](actorRefOf)
      actorRef <- actorRefOf(TestActors.blackholeProps)
    } yield {
      (probe, actorRef)
    }

    resources.use { case (probe, from) =>
      val reply = ReplyStatus.fromActorRef[F](probe.actorEffect.toUnsafe, from.some).mapK(FunctionK.id)
      val error: Throwable = new RuntimeException with NoStackTrace
      for {
        a <- probe.expect
        _ <- reply.success("msg")
        a <- a
        _  = a shouldEqual Probe.Envelop(Status.Success("msg"), from)
        a <- probe.expect
        _ <- reply.fail(error)
        a <- a
        _  = a shouldEqual Probe.Envelop(Status.Failure(error), from)
      } yield {}
    }
  }
}

