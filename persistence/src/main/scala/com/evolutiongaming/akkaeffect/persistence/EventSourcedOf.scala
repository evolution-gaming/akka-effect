package com.evolutiongaming.akkaeffect.persistence

import cats.Monad
import cats.implicits._
import com.evolutiongaming.akkaeffect.ActorCtx

/**
  * This is the very first thing which is called within an actor in order to setup all machinery
  *
  * @tparam S snapshot
  * @tparam E event
  * @tparam C command
  */
trait EventSourcedOf[F[_], S, E, C] {

  def apply(actorCtx: ActorCtx[F]): F[EventSourced[F, S, E, C]]
}

object EventSourcedOf {

  def const[F[_], S, E, C](
    eventSourced: F[EventSourced[F, S, E, C]]
  ): EventSourcedOf[F, S, E, C] = {
    _ => eventSourced
  }

  def apply[F[_], S, E, C](
    f: ActorCtx[F] => F[EventSourced[F, S, E, C]]
  ): EventSourcedOf[F, S, E, C] = {
    actorCtx => f(actorCtx)
  }


  implicit class EventSourcedOfOps[F[_], S, E, C](
    val self: EventSourcedOf[F, S, E, C]
  ) extends AnyVal {

    def convert[S1, E1, C1](
      sf: S => F[S1],
      s1f: S1 => F[S],
      ef: E => F[E1],
      e1f: E1 => F[E],
      c1f: C1 => F[C])(implicit
      F: Monad[F]
    ): EventSourcedOf[F, S1, E1, C1] = {
      actorCtx => self(actorCtx).map { _.convert(sf, s1f, ef, e1f, c1f) }
    }


    def widen[S1 >: S, C1 >: C, E1 >: E](
      sf: Any => F[S],
      ef: Any => F[E],
      cf: Any => F[C])(implicit
      F: Monad[F],
    ): EventSourcedOf[F, S1, E1, C1] = {
      actorCtx => self(actorCtx).map { _.widen(sf, ef, cf) }
    }


    def typeless(
      sf: Any => F[S],
      ef: Any => F[E],
      cf: Any => F[C])(implicit
      F: Monad[F],
    ): EventSourcedOf[F, Any, Any, Any] = widen(sf, ef, cf)
  }
}
