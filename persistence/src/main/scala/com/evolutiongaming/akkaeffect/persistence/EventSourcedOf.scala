package com.evolutiongaming.akkaeffect.persistence

import cats.Monad
import cats.implicits._
import com.evolutiongaming.akkaeffect.ActorCtx

/**
  * This is the very first thing which is called within an actor in order to setup all machinery
  *
  * @tparam S snapshot
  * @tparam C command
  * @tparam E event
  */
trait EventSourcedOf[F[_], S, C, E] {

  def apply(actorCtx: ActorCtx[F]): F[EventSourced[F, S, C, E]]
}

object EventSourcedOf {

  def const[F[_], S, C, E](
    eventSourced: F[EventSourced[F, S, C, E]]
  ): EventSourcedOf[F, S, C, E] = {
    _ => eventSourced
  }

  def apply[F[_], S, C, E](
    f: ActorCtx[F] => F[EventSourced[F, S, C, E]]
  ): EventSourcedOf[F, S, C, E] = {
    actorCtx => f(actorCtx)
  }


  implicit class EventSourcedOfOps[F[_], S, C, E](
    val self: EventSourcedOf[F, S, C, E]
  ) extends AnyVal {

    def convert[S1, C1, E1](
      sf: S => F[S1],
      s1f: S1 => F[S],
      c1f: C1 => F[C],
      ef: E => F[E1],
      e1f: E1 => F[E])(implicit
      F: Monad[F]
    ): EventSourcedOf[F, S1, C1, E1] = {
      actorCtx => self(actorCtx).map { _.convert(sf, s1f, c1f, ef, e1f) }
    }


    def widen[S1 >: S, C1 >: C, E1 >: E](
      sf: Any => F[S],
      cf: Any => F[C],
      ef: Any => F[E])(implicit
      F: Monad[F],
    ): EventSourcedOf[F, S1, C1, E1] = {
      actorCtx => self(actorCtx).map { _.widen(sf, cf, ef) }
    }


    def typeless(
      sf: Any => F[S],
      cf: Any => F[C],
      ef: Any => F[E])(implicit
      F: Monad[F],
    ): EventSourcedOf[F, Any, Any, Any] = widen(sf, cf, ef)
  }
}
