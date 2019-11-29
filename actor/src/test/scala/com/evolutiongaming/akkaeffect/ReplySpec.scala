package com.evolutiongaming.akkaeffect

import akka.actor.ActorSystem
import akka.testkit.TestActors
import cats.arrow.FunctionK
import cats.effect.{Concurrent, IO, Sync}
import cats.implicits._
import com.evolutiongaming.akkaeffect.IOSuite._
import com.evolutiongaming.catshelper.{FromFuture, ToFuture}
import org.scalatest.funsuite.AsyncFunSuite
import org.scalatest.matchers.should.Matchers

class ReplySpec extends AsyncFunSuite with ActorSuite with Matchers {

  test("toString") {
    `toString`[IO](actorSystem).run()
  }

  test("apply") {
    `apply`[IO](actorSystem).run()
  }

  private def `toString`[F[_] : Concurrent](actorSystem: ActorSystem) = {

    val actorRefOf = ActorRefOf[F](actorSystem)
    val actorRef = actorRefOf(TestActors.blackholeProps)
    (actorRef, actorRef).tupled.use { case (to, from) =>
      val reply = Reply.fromActorRef[F](to, from.some)
      Sync[F].delay {
        reply.toString shouldEqual s"Reply(${ to.path }, ${ from.path })"
      }
    }
  }

  private def `apply`[F[_] : Concurrent : ToFuture : FromFuture ](actorSystem: ActorSystem) = {
    val actorRefOf = ActorRefOf[F](actorSystem)
    val resources = for {
      probe    <- Probe.of[F](actorSystem)
      actorRef <- actorRefOf(TestActors.blackholeProps)
    } yield {
      (probe, actorRef)
    }

    resources.use { case (probe, from) =>
      val reply = Reply.fromActorRef[F](probe.actorRef, from.some).mapK(FunctionK.id)
      for {
        a <- probe.expect
        _ <- reply("msg")
        a <- a
        _ <- Sync[F].delay { a shouldEqual Probe.Envelop("msg", from) }
      } yield {}
    }
  }
}
