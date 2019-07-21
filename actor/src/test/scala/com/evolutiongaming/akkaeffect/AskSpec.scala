package com.evolutiongaming.akkaeffect

import akka.actor.ActorSystem
import akka.testkit.TestActors
import cats.arrow.FunctionK
import cats.effect.{Concurrent, IO, Resource, Sync}
import cats.implicits._
import com.evolutiongaming.akkaeffect.IOSuite._
import com.evolutiongaming.catshelper.{FromFuture, ToFuture}
import org.scalatest.{AsyncFunSuite, Matchers}
import scala.concurrent.duration._


class AskSpec extends AsyncFunSuite with ActorSuite with Matchers {

  test("toString") {
    `toString`[IO](actorSystem).run()
  }

  test("apply") {
    `apply`[IO](actorSystem).run()
  }

  private def `toString`[F[_] : Sync : FromFuture](actorSystem: ActorSystem) = {
    val actorRef = actorRefOf[F](actorSystem)
    actorRef.use { actorRef =>
      val ask = Ask.fromActorRef[F](actorRef)
      Sync[F].delay {
        ask.toString shouldEqual s"Ask(${ actorRef.path })"
      }
    }
  }

  private def `apply`[F[_] : Concurrent : ToFuture : FromFuture ](actorSystem: ActorSystem) = {
    val timeout = 1.second

    actorRefOf[F](actorSystem).use { actorRef =>
      val ask = Ask.fromActorRef[F](actorRef).mapK(FunctionK.id)
      for {
        reply <- ask("msg0", timeout)
        _     <- Sync[F].delay { reply shouldEqual "msg0" }
        reply <- ask("msg1", timeout, actorRef.some)
        _     <- Sync[F].delay { reply shouldEqual "msg1" }
      } yield {}
    }
  }

  private def actorRefOf[F[_] : Sync](actorSystem: ActorSystem) = {
    val props = TestActors.echoActorProps
    Resource.make {
      Sync[F].delay { actorSystem.actorOf(props) }
    } { actorRef =>
      Sync[F].delay { actorSystem.stop(actorRef) }
    }
  }
}