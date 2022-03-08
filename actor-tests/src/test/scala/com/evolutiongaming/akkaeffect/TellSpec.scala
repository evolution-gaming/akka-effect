package com.evolutiongaming.akkaeffect

import akka.actor.ActorSystem
import akka.testkit.TestActors
import cats.arrow.FunctionK
import cats.effect.unsafe.implicits.global
import cats.effect.{Async, IO, Sync}
import cats.syntax.all._
import com.evolutiongaming.akkaeffect.IOSuite._
import com.evolutiongaming.akkaeffect.testkit.Probe
import com.evolutiongaming.catshelper.{FromFuture, ToFuture}
import org.scalatest.funsuite.AsyncFunSuite
import org.scalatest.matchers.should.Matchers


class TellSpec extends AsyncFunSuite with ActorSuite with Matchers {

  test("toString") {
    `toString`[IO](actorSystem).run()
  }

  test("fromActorRef") {
    `fromActorRef`[IO](actorSystem).run()
  }

  private def `toString`[F[_] : Sync](actorSystem: ActorSystem) = {
    val actorRefOf = ActorRefOf.fromActorRefFactory[F](actorSystem)
    val actorRef = actorRefOf(TestActors.blackholeProps)
    actorRef.use { actorRef =>
      val tell = Tell.fromActorRef[F](actorRef)
      Sync[F].delay {
        tell.toString shouldEqual s"Tell(${ actorRef.path })"
      }
    }
  }

  private def `fromActorRef`[F[_] : Async : ToFuture : FromFuture ](actorSystem: ActorSystem) = {
    val actorRefOf = ActorRefOf.fromActorRefFactory[F](actorSystem)
    val resources = for {
      actorRef <- actorRefOf(TestActors.blackholeProps)
      probe    <- Probe.of[F](actorRefOf)
    } yield {
      (actorRef, probe)
    }

    resources.use { case (actorRef, probe) =>
      val tell = Tell.fromActorRef[F](probe.actorEffect.toUnsafe).mapK(FunctionK.id)
      for {
        envelope <- probe.expect[String]
        _        <- tell("msg0")
        envelope <- envelope
        _        <- Sync[F].delay { envelope shouldEqual Envelope("msg0", actorSystem.deadLetters) }
        envelope <- probe.expect[String]
        _        <- tell("msg1", actorRef.some)
        envelope <- envelope
        _        <- Sync[F].delay { envelope shouldEqual Envelope("msg1", actorRef) }
      } yield {}
    }
  }
}
