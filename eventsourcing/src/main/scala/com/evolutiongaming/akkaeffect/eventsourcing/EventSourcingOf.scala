package com.evolutiongaming.akkaeffect.eventsourcing

import com.evolutiongaming.akkaeffect.ActorCtx

trait EventSourcingOf[F[_], S, C, E, R] {

  def apply(ctx: ActorCtx[F, C, R]): F[EventSourcing[F, S, C, E, R]]
}
