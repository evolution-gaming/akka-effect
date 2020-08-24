package com.evolutiongaming.akkaeffect

import akka.actor.ActorRef
import cats.effect.Sync
import cats.implicits._
import cats.{FlatMap, Monad, ~>}

/**
  * Api for Actor.receive
  *
  * @see [[akka.actor.Actor.receive]]
  * @tparam A message
  * @tparam B result
  */
trait Receive[F[_], -A, B] {

  /**
    * Called strictly sequentially, next message will be processed only after we've done with the previous one
    * This basically preserves the semantic of Actors
    */
  def apply(msg: A): F[B]
}

object Receive {

  def apply[A]: Apply[A] = new Apply[A]

  private[Receive] final class Apply[A](private val b: Boolean = true) extends AnyVal {

    def apply[F[_], B](f: A => F[B]): Receive[F, A, B] = a => f(a)
  }


  def const[A]: ConstApply[A] = new ConstApply[A]

  private[Receive] final class ConstApply[A](private val b: Boolean = true) extends AnyVal {

    def apply[F[_], B](b: F[B]): Receive[F, A, B] = _ => b
  }


  implicit class ReceiveOps[F[_], A, B](val self: Receive[F, A, B]) extends AnyVal {

    def mapK[G[_]](f: F ~> G): Receive[G, A, B] = a => f(self(a))


    def convert[A1, B1](af: A1 => F[A], bf: B => F[B1])(implicit F: FlatMap[F]): Receive[F, A1, B1] = {
      a => {
        for {
          a <- af(a)
          b <- self(a)
          b <- bf(b)
        } yield b
      }
    }
  }


  implicit class ReceiveCallOps[F[_], A, B, C](val self: Receive[F, Call[F, A, B], C]) extends AnyVal {

    def toReceiveEnvelope(from: Option[ActorRef])(implicit F: Sync[F]): Receive[F, Envelope[A], C] = {
      Receive[Envelope[A]] { a =>
        val reply = Reply.fromActorRef(a.from, from)
        self(Call(a.msg, reply))
      }
    }

    def convert[A1, B1, C1](
      af: A1 => F[A],
      bf: B => F[B1],
      cf: C => F[C1])(implicit
      F: Monad[F]
    ): Receive[F, Call[F, A1, B1], C1] = {
      ReceiveOps(self).convert[Call[F, A1, B1], C1](_.convert(af, bf), cf)
    }
  }


  implicit class ReceiveEnvelopeOps[F[_], A, B](val self: Receive[F, Envelope[A], B]) extends AnyVal {

    def convert[A1, B1](af: A1 => F[A], bf: B => F[B1])(implicit F: FlatMap[F]): Receive[F, Envelope[A1], B1] = {
      Receive[Envelope[A1]] { envelope =>
        for {
          a <- af(envelope.msg)
          b <- self(envelope.copy(msg = a))
          b <- bf(b)
        } yield b
      }
    }
  }
}