package com.evolutiongaming.akkaeffect

import akka.actor.ActorSystem
import akka.testkit.{TestActors, TestProbe}
import cats.arrow.FunctionK
import cats.effect.{IO, Resource, Sync}
import cats.implicits._
import com.evolutiongaming.akkaeffect.IOSuite._
import org.scalatest.{AsyncFunSuite, Matchers}


class TellSpec extends AsyncFunSuite with ActorSuite with Matchers {

  test("toString") {
    `toString`[IO](actorSystem).run()
  }

  test("apply") {
    `apply`[IO](actorSystem).run()
  }

  private def `toString`[F[_] : Sync](actorSystem: ActorSystem) = {

    val actorRef = actorRefOf[F](actorSystem)

    actorRef.use { actorRef =>
      val tell = Tell.fromActorRef[F](actorRef)
      Sync[F].delay {
        tell.toString shouldEqual s"Tell(${ actorRef.path })"
      }
    }
  }

  private def `apply`[F[_] : Sync](actorSystem: ActorSystem) = {

    val actorRef = actorRefOf[F](actorSystem)

    actorRef.use { actorRef =>
      for {
        probe   <- Sync[F].delay { TestProbe()(actorSystem) }
        tell     = Tell.fromActorRef[F](probe.ref).mapK(FunctionK.id)
        _       <- tell("msg0")
        msg0    <- Sync[F].delay { probe.expectMsgType[String] }
        sender0 <- Sync[F].delay { probe.lastSender }
        _       <- tell("msg1", actorRef.some)
        msg1    <- Sync[F].delay { probe.expectMsgType[String] }
        sender1 <- Sync[F].delay { probe.lastSender }
      } yield {
        msg0 shouldEqual "msg0"
        sender0 shouldEqual actorSystem.deadLetters
        msg1 shouldEqual "msg1"
        sender1 shouldEqual actorRef
      }
    }
  }

  private def actorRefOf[F[_] : Sync](actorSystem: ActorSystem) = {
    val props = TestActors.blackholeProps
    Resource.make {
      Sync[F].delay { actorSystem.actorOf(props) }
    } { actorRef =>
      Sync[F].delay { actorSystem.stop(actorRef) }
    }
  }
}
