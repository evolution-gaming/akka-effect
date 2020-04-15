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

  def apply(actorCtx: ActorCtx[F]): F[EventSourced[F, S, C, E, R]]
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
      cf: C1 => F[C],
      ef: E1 => F[E],
      rf: R => F[R1]
    )(implicit
      F: Monad[F]
    ): EventSourcedOf[F, S1, C1, E1, R1] = {
      actorCtx: ActorCtx[F] => {
        for {
          eventSourced <- self(actorCtx)
        } yield {
          eventSourced.convert(sf, s1f, cf, ef, rf)
        }
      }
    }


    def widen[S1 >: S, C1 >: C, E1 >: E, R1 >: R](
      sf: Any => F[S],
      cf: Any => F[C],
      ef: Any => F[E])(implicit
      F: Monad[F],
    ): EventSourcedOf[F, S1, C1, E1, R1] = {
      actorCtx: ActorCtx[F] => {
        for {
          eventSourced <- self(actorCtx)
        } yield {
          eventSourced.widen(sf, cf, ef)
        }
      }
    }


    def typeless(
      sf: Any => F[S],
      cf: Any => F[C],
      ef: Any => F[E])(implicit
      F: Monad[F],
    ): EventSourcedOf[F, Any, Any, Any, Any] = widen(sf, cf, ef)
  }
}