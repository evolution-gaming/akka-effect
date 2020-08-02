package com.evolutiongaming.akkaeffect.util

import cats.effect.implicits._
import cats.effect.{Concurrent, IO, Timer}
import cats.implicits._
import com.evolutiongaming.akkaeffect.IOSuite._
import com.evolutiongaming.akkaeffect.{ActorEffect, ActorRefOf, ActorSuite, Receive1Of}
import com.evolutiongaming.catshelper.{FromFuture, ToFuture}
import org.scalatest.funsuite.AsyncFunSuite
import org.scalatest.matchers.should.Matchers

import scala.concurrent.TimeoutException
import scala.concurrent.duration._


class TerminatedTest extends AsyncFunSuite with ActorSuite with Matchers {

  test("wait for termination") {
    `wait for termination`[IO].run()
  }

  test("already dead actors") {
    `already dead actors`[IO].run()
  }


  def `wait for termination`[F[_]: Concurrent: Timer: ToFuture: FromFuture]: F[Unit] = {

    val actorRefOf = ActorRefOf.fromActorRefFactory[F](actorSystem)

    val terminatedActor = Terminated(actorRefOf)

    ActorEffect
      .of(actorRefOf, Receive1Of.empty[F, Any, Any])
      .use { actorEffect =>
        for {
          fiber <- terminatedActor(actorEffect).start
          a     <- fiber.join.timeout(10.millis).attempt
          _       = a should matchPattern { case Left(_: TimeoutException) => }
        } yield {
          fiber.join
        }
      }
      .flatten
  }


  def `already dead actors`[F[_]: Concurrent: Timer: ToFuture: FromFuture]: F[Unit] = {
    val actorRefOf = ActorRefOf.fromActorRefFactory[F](actorSystem)

    val terminatedActor = Terminated(actorRefOf)

    ActorEffect
      .of(actorRefOf, Receive1Of.empty[F, Any, Any])
      .use { actorEffect => terminatedActor(actorEffect).pure[F] }
      .flatten
  }
}
