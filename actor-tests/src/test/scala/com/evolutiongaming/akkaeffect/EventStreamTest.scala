package com.evolutiongaming.akkaeffect

import cats.effect.concurrent.Deferred
import cats.effect.{Concurrent, IO}
import cats.syntax.all._
import com.evolutiongaming.catshelper.ToFuture
import com.evolutiongaming.akkaeffect.IOSuite._
import org.scalatest.funsuite.AsyncFunSuite
import org.scalatest.matchers.should.Matchers

class EventStreamTest extends AsyncFunSuite with ActorSuite with Matchers {

  test("publish & subscribe") {
    publishAndSubscribe[IO].run()
  }

  private def publishAndSubscribe[F[_]: Concurrent: ToFuture] = {

    case class Event(n: Int)

    val eventStream = EventStream[F](actorSystem)
    for {
      deferred <- Deferred[F, Event]
      onEvent   = (event: Event) => deferred.complete(event)
      actual   <- eventStream.subscribe(onEvent).use { _ =>
        eventStream
          .publish(Event(0))
          .productR(deferred.get)
      }
    } yield {
      actual shouldEqual Event(0)
    }
  }
}
