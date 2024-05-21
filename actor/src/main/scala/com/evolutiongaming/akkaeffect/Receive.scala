package com.evolutiongaming.akkaeffect

import akka.actor.ActorRef
import cats.effect.Sync
import cats.syntax.all.*
import cats.{Contravariant, FlatMap, Functor, Monad, ~>}

/** Api for Actor.receive
  *
  * @see
  *   [[akka.actor.Actor.receive]]
  * @tparam A
  *   message
  * @tparam B
  *   result
  */
trait Receive[F[_], -A, B] {

  /** Called strictly sequentially, next message will be processed only after we've done with the previous one This
    * basically preserves the semantic of Actors
    */
  def apply(msg: A): F[B]

  /** @see
    *   [[akka.actor.ReceiveTimeout]]
    */
  def timeout: F[B]
}

object Receive {

  implicit def functorReceive[F[_]: Functor, A]: Functor[Receive[F, A, *]] = new Functor[Receive[F, A, *]] {
    def map[B, B1](fa: Receive[F, A, B])(f: B => B1): Receive[F, A, B1] = fa.map(f)
  }

  implicit def contravariantReceive[F[_], B]: Contravariant[Receive[F, *, B]] =
    new Contravariant[Receive[F, *, B]] {
      def contramap[A, A1](fa: Receive[F, A, B])(f: A1 => A) = fa.contramap(f)
    }

  def apply[A]: Apply[A] = new Apply[A]

  final private[Receive] class Apply[A](private val b: Boolean = true) extends AnyVal {

    def apply[F[_], B](receive: A => F[B])(timeout: => F[B]): Receive[F, A, B] = {
      def timeout1 = timeout
      new Receive[F, A, B] {

        def apply(msg: A) = receive(msg)

        def timeout = timeout1
      }
    }
  }

  def const[A]: Const[A] = new Const[A]

  final private[Receive] class Const[A](private val b: Boolean = true) extends AnyVal {

    def apply[F[_], B](b: F[B]): Receive[F, A, B] = new Receive[F, A, B] {

      def apply(msg: A) = b

      def timeout = b
    }
  }

  implicit class ReceiveOps[F[_], A, B](val self: Receive[F, A, B]) extends AnyVal {

    def mapK[G[_]](f: F ~> G): Receive[G, A, B] = new Receive[G, A, B] {

      def apply(msg: A) = f(self(msg))

      def timeout = f(self.timeout)
    }

    def map[B1](f: B => B1)(implicit F: Functor[F]): Receive[F, A, B1] = new Receive[F, A, B1] {

      def apply(msg: A) = self(msg).map(f)

      def timeout = self.timeout.map(f)
    }

    def mapM[B1](f: B => F[B1])(implicit F: FlatMap[F]): Receive[F, A, B1] = new Receive[F, A, B1] {

      def apply(msg: A) = self(msg).flatMap(f)

      def timeout = self.timeout.flatMap(f)
    }

    def contramap[A1](f: A1 => A): Receive[F, A1, B] = new Receive[F, A1, B] {

      def apply(msg: A1) = self(f(msg))

      def timeout = self.timeout
    }

    def contramapM[A1](f: A1 => F[A])(implicit F: FlatMap[F]): Receive[F, A1, B] = new Receive[F, A1, B] {

      def apply(msg: A1) = f(msg).flatMap(a => self(a))

      def timeout = self.timeout
    }

    def convert[A1, B1](af: A1 => F[A], bf: B => F[B1])(implicit
      F: FlatMap[F],
    ): Receive[F, A1, B1] = new Receive[F, A1, B1] {

      def apply(msg: A1) =
        for {
          a <- af(msg)
          b <- self(a)
          b <- bf(b)
        } yield b

      def timeout =
        for {
          b <- self.timeout
          b <- bf(b)
        } yield b
    }
  }

  implicit class ReceiveCallOps[F[_], A, B, C](val self: Receive[F, Call[F, A, B], C]) extends AnyVal {

    def toReceiveEnvelope(from: Option[ActorRef])(implicit F: Sync[F]): Receive[F, Envelope[A], C] =
      self.contramap[Envelope[A]] { a =>
        val reply = Reply.fromActorRef(a.from, from)
        Call(a.msg, reply)
      }

    def convert[A1, B1, C1](af: A1 => F[A], bf: B => F[B1], cf: C => F[C1])(implicit
      F: Monad[F],
    ): Receive[F, Call[F, A1, B1], C1] =
      ReceiveOps(self).convert[Call[F, A1, B1], C1](_.convert(af, bf), cf)
  }

  implicit class ReceiveEnvelopeOps[F[_], A, B](val self: Receive[F, Envelope[A], B]) extends AnyVal {

    def convert[A1, B1](af: A1 => F[A], bf: B => F[B1])(implicit
      F: FlatMap[F],
    ): Receive[F, Envelope[A1], B1] = {

      def a1f(envelope: Envelope[A1]) =
        af(envelope.msg).map(a => envelope.copy(msg = a))

      ReceiveOps(self).convert[Envelope[A1], B1](a1f, bf)
    }
  }
}
