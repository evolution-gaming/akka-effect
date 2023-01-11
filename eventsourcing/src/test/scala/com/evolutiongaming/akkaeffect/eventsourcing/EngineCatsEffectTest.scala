package com.evolutiongaming.akkaeffect.eventsourcing

import cats.effect._
import com.evolutiongaming.catshelper.{FromFuture, ToFuture}

class EngineCatsEffectTest extends EngineTestCases {

  override def engine[F[_]: Async: ToFuture: FromFuture, S, E](
    initial: Engine.State[S],
    append: Engine.Append[F, E]
  ): Resource[F, Engine[F, S, E]] = Engine.of[F, S, E](initial, append)
}
