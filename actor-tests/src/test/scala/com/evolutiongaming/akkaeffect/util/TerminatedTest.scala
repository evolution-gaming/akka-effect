package com.evolutiongaming.akkaeffect.util

import akka.actor.ActorSystem
import cats.effect.implicits._
import cats.effect.{Concurrent, IO, Resource, Timer}
import cats.syntax.all._
import com.evolutiongaming.akkaeffect.IOSuite._
import com.evolutiongaming.akkaeffect.{ActorEffect, ActorRefOf, ActorSuite, Call, Receive, ReceiveOf}
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

  test("already dead actor system") {
    `already dead actor system`[IO].run()
  }


  def `wait for termination`[F[_]: Concurrent: Timer: ToFuture: FromFuture]: F[Unit] = {

    val actorRefOf = ActorRefOf.fromActorRefFactory[F](actorSystem)

    val terminatedActor = Terminated(actorRefOf)

    ActorEffect
      .of(actorRefOf, ReceiveOf.const(Receive.const[Call[F, Any, Any]](false.pure[F]).pure[Resource[F, *]]))
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


  def `already dead actors`[F[_]: Concurrent: ToFuture: FromFuture]: F[Unit] = {
    val actorRefOf = ActorRefOf.fromActorRefFactory[F](actorSystem)

    val terminatedActor = Terminated(actorRefOf)

    ActorEffect
      .of(actorRefOf, ReceiveOf.const(Receive.const[Call[F, Any, Any]](false.pure[F]).pure[Resource[F, *]]))
      .use { actorEffect => terminatedActor(actorEffect).pure[F] }
      .flatten
  }

  def `already dead actor system`[F[_]: Concurrent: ToFuture: FromFuture]: F[Unit] = {
    val actorSystem = ActorSystem()
    val actorRefOf = ActorRefOf.fromActorRefFactory[F](actorSystem)

    val terminatedActor = Terminated(actorRefOf)

    ActorEffect
      .of(actorRefOf, ReceiveOf.const(Receive.const[Call[F, Any, Any]](false.pure[F]).pure[Resource[F, *]]))
      .use { actorEffect => FromFuture[F].apply(actorSystem.terminate()) *> terminatedActor(actorEffect).pure[F] }
      .flatten
  }
}
