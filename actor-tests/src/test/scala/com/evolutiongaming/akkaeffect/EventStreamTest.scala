package com.evolutiongaming.akkaeffect

import cats.effect.kernel.Deferred
import cats.effect.unsafe.implicits.global
import cats.effect.{Async, IO}
import cats.syntax.all.*
import com.evolutiongaming.akkaeffect.IOSuite.*
import com.evolutiongaming.catshelper.ToFuture
import org.scalatest.funsuite.AsyncFunSuite
import org.scalatest.matchers.should.Matchers

class EventStreamTest extends AsyncFunSuite with ActorSuite with Matchers {

  test("publish & subscribe") {
    publishAndSubscribe[IO].run()
  }

  private def publishAndSubscribe[F[_]: Async: ToFuture] = {

    case class Event(n: Int)

    val eventStream = EventStream[F](actorSystem)
    for {
      deferred <- Deferred[F, Event]
      onEvent   = (event: Event) => deferred.complete(event).void
      actual <- eventStream.subscribe(onEvent).use { _ =>
        eventStream
          .publish(Event(0))
          .productR(deferred.get)
      }
    } yield actual shouldEqual Event(0)
  }
}
