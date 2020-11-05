package com.evolutiongaming.akkaeffect

import akka.actor.ActorSystem
import akka.testkit.TestActors
import cats.arrow.FunctionK
import cats.effect.{Concurrent, IO, Sync}
import cats.syntax.all._
import com.evolutiongaming.akkaeffect.IOSuite._
import com.evolutiongaming.catshelper.{FromFuture, ToFuture}
import org.scalatest.funsuite.AsyncFunSuite
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._


class AskTest extends AsyncFunSuite with ActorSuite with Matchers {

  test("toString") {
    `toString`[IO](actorSystem).run()
  }

  test("apply") {
    `apply`[IO](actorSystem).run()
  }

  private def `toString`[F[_] : Sync : FromFuture](actorSystem: ActorSystem) = {
    val actorRefOf = ActorRefOf.fromActorRefFactory[F](actorSystem)
    actorRefOf(TestActors.echoActorProps).use { actorRef =>
      val ask = Ask.fromActorRef[F](actorRef)
      Sync[F].delay {
        ask.toString shouldEqual s"Ask(${ actorRef.path })"
      }
    }
  }

  private def `apply`[F[_] : Concurrent : ToFuture : FromFuture ](actorSystem: ActorSystem) = {
    val timeout = 1.second
    val actorRefOf = ActorRefOf.fromActorRefFactory[F](actorSystem)
    actorRefOf(TestActors.echoActorProps).use { actorRef =>
      val ask = Ask.fromActorRef[F](actorRef).mapK(FunctionK.id)
      for {
        a0 <- ask("msg0", timeout)
        a1 <- ask("msg1", timeout, actorRef.some)
        a  <- a0
        _   = a shouldEqual "msg0"
        a  <- a1
        _   = a shouldEqual "msg1"
      } yield {}
    }
  }
}