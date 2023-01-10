package com.evolutiongaming.akkaeffect.eventsourcing


import akka.stream.SystemMaterializer
import cats.effect._
import cats.effect.implicits.effectResourceOps
import cats.effect.unsafe.implicits.global
import com.evolutiongaming.akkaeffect.ActorSuite
import com.evolutiongaming.akkaeffect.IOSuite._
import com.evolutiongaming.catshelper.{FromFuture, ToFuture}
import org.scalatest.funsuite.AsyncFunSuite

class EngineAkkaTest extends AsyncFunSuite with EngineTestCases with ActorSuite {

  test("order of stages") {
    `order of stages`[IO].run()
  }

  test("append error prevents further appends") {
    `append error prevents further appends`[IO].run()
  }

  test("client errors do not have global impact") {
    `client errors do not have global impact`[IO].run()
  }

  test("after stop engine finishes with inflight elements and releases") {
    `after stop engine finishes with inflight elements and releases`[IO].run()
  }

  test("release finishes with inflight elements") {
    `release finishes with inflight elements`[IO].run()
  }

  override def engine[F[_]: Async: ToFuture: FromFuture, S, E](
    initial: Engine.State[S],
    append: Engine.Append[F, E]
  ): Resource[F, Engine[F, S, E]] =
    for {
      materializer <- Sync[F].delay { SystemMaterializer(actorSystem).materializer }.toResource
      engine       <- Engine.of[F, S, E](initial, materializer, append)
    } yield engine
}
