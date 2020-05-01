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
  * @tparam R reply
  */
trait EventSourcedAnyOf[F[_], S, C, E, R] {

  def apply(actorCtx: ActorCtx[F]): F[EventSourcedAny[F, S, C, E, R]]
}

object EventSourcedAnyOf {

  def const[F[_]: Applicative, S, C, E, R](
    eventSourced: EventSourcedAny[F, S, C, E, R]
  ): EventSourcedAnyOf[F, S, C, E, R] = {
    _ => eventSourced.pure[F]
  }

  def apply[F[_], S, C, E, R](
    f: ActorCtx[F] => F[EventSourcedAny[F, S, C, E, R]]
  ): EventSourcedAnyOf[F, S, C, E, R] = {
    actorCtx => f(actorCtx)
  }
}
