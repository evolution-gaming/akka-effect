package com.evolutiongaming.akkaeffect.persistence

import cats.effect.Resource
import cats.implicits._
import cats.{Functor, Monad}
import com.evolutiongaming.akkaeffect.{ActorCtx, Envelope, Receive}

/**
  * This is the very first thing which is called within an actor in order to setup all machinery
  *
  * @tparam A usually something used to construct instance of an actor
  */
trait EventSourcedOf[F[_], A] {

  def apply(actorCtx: ActorCtx[F]): F[EventSourced[A]]
}

object EventSourcedOf {

  implicit def functorEventSourcedOf[F[_]: Functor]: Functor[EventSourcedOf[F, *]] = new Functor[EventSourcedOf[F, *]] {
    def map[A, B](fa: EventSourcedOf[F, A])(f: A => B) = fa.map(f)
  }


  def const[F[_], A](eventSourced: F[EventSourced[A]]): EventSourcedOf[F, A] = _ => eventSourced


  def apply[F[_]]: Apply[F] = new Apply[F]

  private[EventSourcedOf] final class Apply[F[_]](private val b: Boolean = true) extends AnyVal {

    def apply[A](
      f: ActorCtx[F] => F[EventSourced[A]]
    ): EventSourcedOf[F, A] = {
      actorCtx => f(actorCtx)
    }
  }


  implicit class EventSourcedOfOps[F[_], A](val self: EventSourcedOf[F, A]) extends AnyVal {

    def map[B](f: A => B)(implicit F: Functor[F]): EventSourcedOf[F, B] = {
      actorCtx => self(actorCtx).map { _.map(f) }
    }
  }


  implicit class EventSourcedOfRecoveryStartedOps[F[_], S, E, A](
    val self: EventSourcedOf[F, Resource[F, RecoveryStarted[F, S, E, A]]]
  ) extends AnyVal {

    def convert[S1, E1, A1](
      sf: S => F[S1],
      s1f: S1 => F[S],
      ef: E => F[E1],
      e1f: E1 => F[E],
      af: A => Resource[F, A1])(implicit
      F: Monad[F]
    ): EventSourcedOf[F, Resource[F, RecoveryStarted[F, S1, E1, A1]]] = {
      self.map { _.map { _.convert(sf, s1f, ef, e1f, af) } }
    }
  }


  implicit class EventSourcedOfReceiveEnvelopeOps[F[_], S, E, C](
    val self: EventSourcedOf[F, Resource[F, RecoveryStarted[F, S, E, Receive[F, Envelope[C], Boolean]]]]
  ) extends AnyVal {

    def widen[S1 >: S, C1 >: C, E1 >: E](
      sf: Any => F[S],
      ef: Any => F[E],
      cf: Any => F[C])(implicit
      F: Monad[F],
    ): EventSourcedOf[F, Resource[F, RecoveryStarted[F, S1, E1, Receive[F, Envelope[C1], Boolean]]]] = {
      self.map { _.map { _.widen(sf, ef, cf) } }
    }


    def typeless(
      sf: Any => F[S],
      ef: Any => F[E],
      cf: Any => F[C])(implicit
      F: Monad[F],
    ): EventSourcedOf[F, Resource[F, RecoveryStarted[F, Any, Any, Receive[F, Envelope[Any], Boolean]]]] = {
      widen(sf, ef, cf)
    }
  }
}
