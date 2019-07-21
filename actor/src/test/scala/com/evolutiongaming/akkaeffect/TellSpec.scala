package com.evolutiongaming.akkaeffect

import akka.actor.ActorSystem
import akka.testkit.TestActors
import cats.arrow.FunctionK
import cats.effect.{Concurrent, IO, Resource, Sync}
import cats.implicits._
import com.evolutiongaming.akkaeffect.IOSuite._
import com.evolutiongaming.catshelper.{FromFuture, ToFuture}
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

  private def `apply`[F[_] : Concurrent : ToFuture : FromFuture ](actorSystem: ActorSystem) = {
    val resources = for {
      actorRef <- actorRefOf[F](actorSystem)
      probe    <- Probe.of[F](actorSystem)
    } yield {
      (actorRef, probe)
    }

    resources.use { case (actorRef, probe) =>
      val tell = Tell.fromActorRef[F](probe.actorRef).mapK(FunctionK.id)
      for {
        envelope <- probe.expect
        _        <- tell("msg0")
        envelope <- envelope
        _        <- Sync[F].delay { envelope shouldEqual Probe.Envelop("msg0", actorSystem.deadLetters) }
        envelope <- probe.expect
        _        <- tell("msg1", actorRef.some)
        envelope <- envelope
        _        <- Sync[F].delay { envelope shouldEqual Probe.Envelop("msg1", actorRef) }
      } yield {}
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
