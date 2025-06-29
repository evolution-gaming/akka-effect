package com.evolutiongaming.akkaeffect

import akka.actor.ActorSystem
import akka.testkit.TestActors
import cats.arrow.FunctionK
import cats.effect.unsafe.implicits.global
import cats.effect.{Async, IO, Sync}
import cats.syntax.all.*
import com.evolutiongaming.akkaeffect.IOSuite.*
import com.evolutiongaming.akkaeffect.testkit.Probe
import com.evolutiongaming.catshelper.{FromFuture, ToFuture}
import org.scalatest.funsuite.AsyncFunSuite
import org.scalatest.matchers.should.Matchers

class ReplyTest extends AsyncFunSuite with ActorSuite with Matchers {

  test("toString") {
    `toString`[IO](actorSystem).run()
  }

  test("fromActorRef") {
    `fromActorRef`[IO](actorSystem).run()
  }

  private def `toString`[F[_]: Async](actorSystem: ActorSystem) = {
    val actorRefOf = ActorRefOf.fromActorRefFactory[F](actorSystem)
    val actorRef   = actorRefOf(TestActors.blackholeProps)
    (actorRef, actorRef).tupled.use {
      case (to, from) =>
        val reply = Reply.fromActorRef[F](to = to, from = from)
        Sync[F].delay {
          reply.toString shouldEqual s"Reply(${to.path}, ${from.path})"
        }
    }
  }

  private def `fromActorRef`[F[_]: Async: ToFuture: FromFuture](actorSystem: ActorSystem) = {
    val actorRefOf = ActorRefOf.fromActorRefFactory[F](actorSystem)
    val resources  = for {
      probe    <- Probe.of[F](actorRefOf)
      actorRef <- actorRefOf(TestActors.blackholeProps)
    } yield (probe, actorRef)

    resources.use {
      case (probe, from) =>
        val reply = Reply.fromActorRef[F](to = probe.actorEffect.toUnsafe, from = from).mapK(FunctionK.id)
        for {
          a <- probe.expect[String]
          _ <- reply("msg")
          a <- a
          _ <- Sync[F].delay(a shouldEqual Envelope("msg", from))
        } yield {}
    }
  }
}
