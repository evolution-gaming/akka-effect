package com.evolutiongaming.akkaeffect

import cats.Monad
import cats.effect.{Resource, Sync}
import cats.syntax.all.*

trait ReceiveOf[F[_], -A, B] {

  def apply(actorCtx: ActorCtx[F]): Resource[F, Receive[F, A, B]]
}

object ReceiveOf {

  def apply[F[_]]: Apply[F] = new Apply[F]

  final private[ReceiveOf] class Apply[F[_]](private val b: Boolean = true) extends AnyVal {

    def apply[A, B](
      f: ActorCtx[F] => Resource[F, Receive[F, A, B]],
    ): ReceiveOf[F, A, B] = { actorCtx =>
      f(actorCtx)
    }
  }

  def const[F[_], A, B](a: Resource[F, Receive[F, A, B]]): ReceiveOf[F, A, B] = _ => a

  implicit class ReceiveOfOps[F[_], A, B](val self: ReceiveOf[F, A, B]) extends AnyVal {

    def convert[A1, B1](f: Receive[F, A, B] => Resource[F, Receive[F, A1, B1]]): ReceiveOf[F, A1, B1] =
      ReceiveOf[F](actorCtx => self(actorCtx).flatMap(f))

    def map[A1, B1](f: Receive[F, A, B] => Receive[F, A1, B1]): ReceiveOf[F, A1, B1] =
      ReceiveOf[F](actorCtx => self(actorCtx).map(f))
  }

  implicit class ReceiveOfCallOps[F[_], A, B, C](val self: ReceiveOf[F, Call[F, A, B], C]) extends AnyVal {

    def toReceiveOfEnvelope(implicit F: Sync[F]): ReceiveOf[F, Envelope[A], C] =
      ReceiveOf[F](actorCtx => self(actorCtx).map(_.toReceiveEnvelope(actorCtx.self.some)))

    def convert[A1, B1, C1](af: A1 => F[A], bf: B => F[B1], cf: C => F[C1])(implicit
      F: Monad[F],
    ): ReceiveOf[F, Call[F, A1, B1], C1] =
      self.map(_.convert(af, bf, cf))
  }

  implicit class ReceiveOfEnvelopeOps[F[_], A, B](val self: ReceiveOf[F, Envelope[A], B]) extends AnyVal {

    def convert[A1, B1](af: A1 => F[A], bf: B => F[B1])(implicit
      F: Monad[F],
    ): ReceiveOf[F, Envelope[A1], B1] =
      self.map(_.convert(af, bf))
  }
}
