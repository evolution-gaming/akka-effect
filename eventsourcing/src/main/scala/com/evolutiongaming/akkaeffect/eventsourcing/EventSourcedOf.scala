package com.evolutiongaming.akkaeffect.eventsourcing

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
trait EventSourcedOf[F[_], S, C, E, R] {

  def apply(ctx: ActorCtx[F, C, R]): F[EventSourced[F, S, C, E, R]]
}

object EventSourcedOf {

  def const[F[_] : Applicative, S, C, E, R](
    eventSourced: EventSourced[F, S, C, E, R]
  ): EventSourcedOf[F, S, C, E, R] = {
    _ => eventSourced.pure[F]
  }


  implicit class EventSourcedOfOps[F[_], S, C, E, R](
    val self: EventSourcedOf[F, S, C, E, R]
  ) extends AnyVal {

    def convert[S1, C1, E1, R1](
      sf: S => F[S1],
      s1f: S1 => F[S],
      cf: C => F[C1],
      c1f: C1 => F[C],
      ef: E => F[E1],
      e1f: E1 => F[E],
      rf: R => F[R1],
      r1f: R1 => F[R],
    )(implicit
      F: Monad[F]
    ): EventSourcedOf[F, S1, C1, E1, R1] = {
      ctx: ActorCtx[F, C1, R1] => {
        val ctx1 = ctx.convert(cf, r1f)
        for {
          eventSourced <- self(ctx1)
        } yield {
          eventSourced.convert(sf, s1f, c1f, ef, e1f, rf)
        }
      }
    }


    def widen[S1 >: S, C1 >: C, E1 >: E, R1 >: R](
      sf: Any => F[S],
      cf: Any => F[C],
      ef: Any => F[E],
      rf: Any => F[R])(implicit
      F: Monad[F],
    ): EventSourcedOf[F, S1, C1, E1, R1] = {
      ctx: ActorCtx[F, C1, R1] => {
        val ctx1 = ctx.narrow[C, R](rf)
        for {
          eventSourced <- self(ctx1)
        } yield {
          eventSourced.widen(sf, cf, ef)
        }
      }
    }


    def typeless(
      sf: Any => F[S],
      cf: Any => F[C],
      ef: Any => F[E],
      rf: Any => F[R])(implicit
      F: Monad[F],
    ): EventSourcedOf[F, Any, Any, Any, Any] = widen(sf, cf, ef, rf)
  }
}