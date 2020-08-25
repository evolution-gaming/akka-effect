package com.evolutiongaming.akkaeffect.persistence

import cats.Monad
import cats.effect.Resource
import cats.implicits._
import com.evolutiongaming.akkaeffect.{ActorCtx, Envelope, Receive}

/**
  * This is the very first thing which is called within an actor in order to setup all machinery
  *
  * @tparam S snapshot
  * @tparam E event
  * @tparam A recovery result
  */
trait EventSourcedOf[F[_], S, E, A] {

  def apply(actorCtx: ActorCtx[F]): F[EventSourced[F, S, E, A]]
}

object EventSourcedOf {

  def const[F[_], S, E, A](
    eventSourced: F[EventSourced[F, S, E, A]]
  ): EventSourcedOf[F, S, E, A] = {
    _ => eventSourced
  }


  def apply[F[_]]: Apply[F] = new Apply[F]

  private[EventSourcedOf] final class Apply[F[_]](private val b: Boolean = true) extends AnyVal {

    def apply[S, E, A](
      f: ActorCtx[F] => F[EventSourced[F, S, E, A]]
    ): EventSourcedOf[F, S, E, A] = {
      actorCtx => f(actorCtx)
    }
  }


  implicit class EventSourcedOfOps[F[_], S, E, A](
    val self: EventSourcedOf[F, S, E, A]
  ) extends AnyVal {

    def convert[S1, E1, A1](
      sf: S => F[S1],
      s1f: S1 => F[S],
      ef: E => F[E1],
      e1f: E1 => F[E],
      af: A => Resource[F, A1])(implicit
      F: Monad[F]
    ): EventSourcedOf[F, S1, E1, A1] = {
      actorCtx => self(actorCtx).map { _.convert(sf, s1f, ef, e1f, af) }
    }
  }


  implicit class EventSourcedOfReceiveEnvelopeOps[F[_], S, E, C](
    val self: EventSourcedOf[F, S, E, Receive[F, Envelope[C], Boolean]]
  ) extends AnyVal {

    def widen[S1 >: S, C1 >: C, E1 >: E](
      sf: Any => F[S],
      ef: Any => F[E],
      cf: Any => F[C])(implicit
      F: Monad[F],
    ): EventSourcedOf[F, S1, E1, Receive[F, Envelope[C1], Boolean]] = {
      actorCtx => self(actorCtx).map { _.widen(sf, ef, cf) }
    }


    def typeless(
      sf: Any => F[S],
      ef: Any => F[E],
      cf: Any => F[C])(implicit
      F: Monad[F],
    ): EventSourcedOf[F, Any, Any, Receive[F, Envelope[Any], Boolean]] = {
      widen(sf, ef, cf)
    }
  }
}
