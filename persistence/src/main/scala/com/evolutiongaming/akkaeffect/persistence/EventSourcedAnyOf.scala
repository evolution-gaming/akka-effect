package com.evolutiongaming.akkaeffect.persistence

import cats.implicits._
import cats.{Applicative, Monad}
import com.evolutiongaming.akkaeffect.ActorCtx

/**
  * This is the very first thing which is called within an actor in order to setup all machinery
  *
  * @tparam S snapshot
  * @tparam C command
  * @tparam E event
  */
trait EventSourcedAnyOf[F[_], S, C, E] {

  def apply(actorCtx: ActorCtx[F]): F[EventSourcedAny[F, S, C, E]]
}

object EventSourcedAnyOf {

  def const[F[_]: Applicative, S, C, E, R](
    eventSourced: EventSourcedAny[F, S, C, E]
  ): EventSourcedAnyOf[F, S, C, E] = {
    _ => eventSourced.pure[F]
  }

  def apply[F[_], S, C, E, R](
    f: ActorCtx[F] => F[EventSourcedAny[F, S, C, E]]
  ): EventSourcedAnyOf[F, S, C, E] = {
    actorCtx => f(actorCtx)
  }
}
