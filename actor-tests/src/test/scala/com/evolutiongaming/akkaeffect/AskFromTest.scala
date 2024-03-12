package com.evolutiongaming.akkaeffect

import akka.actor.ActorRef
import cats.effect.unsafe.implicits.global
import cats.effect.{Async, IO, Resource, Sync}
import cats.syntax.all._
import com.evolutiongaming.akkaeffect.IOSuite._
import com.evolutiongaming.akkaeffect.testkit.Probe
import com.evolutiongaming.catshelper.{FromFuture, ToFuture}
import org.scalatest.funsuite.AsyncFunSuite
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._

class AskFromTest extends AsyncFunSuite with ActorSuite with Matchers {

  test("askFrom") {
    askFrom[IO].run()
  }

  private def askFrom[F[_]: Async: ToFuture: FromFuture] = {
    val actorRefOf = ActorRefOf.fromActorRefFactory[F](actorSystem)
    val result = for {
      from    <- Probe.of(actorRefOf)
      to      <- Probe.of(actorRefOf)
      askFrom <- AskFrom.of(actorRefOf, from.actorEffect.toUnsafe, 1.minute)
      result <- Resource.eval {
        for {
          envelope <- to.expect[Any]
          result   <- askFrom[ActorRef, String](to.actorEffect.toUnsafe)(identity)
          envelope <- envelope
          _         = envelope.msg should not equal from.actorEffect.toUnsafe
          _         = envelope.msg shouldEqual envelope.from
          _        <- Sync[F].delay(envelope.from.tell("ok", ActorRef.noSender))
          result   <- result
          _         = result shouldEqual "ok"
        } yield {}
      }
    } yield result
    result.use(_.pure[F])
  }
}
