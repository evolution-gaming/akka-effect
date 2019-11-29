package com.evolutiongaming.akkaeffect

import akka.actor.ActorSystem
import akka.testkit.TestActors
import cats.arrow.FunctionK
import cats.effect.{Concurrent, IO, Sync}
import cats.implicits._
import com.evolutiongaming.akkaeffect.IOSuite._
import com.evolutiongaming.catshelper.{FromFuture, ToFuture}
import scala.concurrent.duration._
import org.scalatest.funsuite.AsyncFunSuite
import org.scalatest.matchers.should.Matchers


class AskSpec extends AsyncFunSuite with ActorSuite with Matchers {

  test("toString") {
    `toString`[IO](actorSystem).run()
  }

  test("apply") {
    `apply`[IO](actorSystem).run()
  }

  private def `toString`[F[_] : Sync : FromFuture](actorSystem: ActorSystem) = {
    val actorRefOf = ActorRefOf[F](actorSystem)
    actorRefOf(TestActors.echoActorProps).use { actorRef =>
      val ask = Ask.fromActorRef[F](actorRef)
      Sync[F].delay {
        ask.toString shouldEqual s"Ask(${ actorRef.path })"
      }
    }
  }

  private def `apply`[F[_] : Concurrent : ToFuture : FromFuture ](actorSystem: ActorSystem) = {
    val timeout = 1.second
    val actorRefOf = ActorRefOf[F](actorSystem)
    actorRefOf(TestActors.echoActorProps).use { actorRef =>
      val ask = Ask.fromActorRef[F](actorRef).mapK(FunctionK.id)
      for {
        reply <- ask("msg0", timeout)
        _     <- Sync[F].delay { reply shouldEqual "msg0" }
        reply <- ask("msg1", timeout, actorRef.some)
        _     <- Sync[F].delay { reply shouldEqual "msg1" }
      } yield {}
    }
  }
}