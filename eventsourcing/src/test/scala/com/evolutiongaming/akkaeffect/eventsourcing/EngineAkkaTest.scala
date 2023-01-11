package com.evolutiongaming.akkaeffect.eventsourcing

import akka.stream.SystemMaterializer
import cats.effect._
import cats.effect.implicits.effectResourceOps
import com.evolutiongaming.akkaeffect.ActorSuite
import com.evolutiongaming.catshelper.{FromFuture, ToFuture}

class EngineAkkaTest extends EngineTestCases with ActorSuite {

  override def engine[F[_]: Async: ToFuture: FromFuture, S, E](
    initial: Engine.State[S],
    append: Engine.Append[F, E]
  ): Resource[F, Engine[F, S, E]] =
    for {
      materializer <- Sync[F].delay {
        SystemMaterializer(actorSystem).materializer
      }.toResource
      engine <- Engine.of[F, S, E](initial, materializer, append)
    } yield engine
}
