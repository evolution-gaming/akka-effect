package com.evolutiongaming.akkaeffect

import cats.implicits._
import cats.{Applicative, FlatMap, Monad, ~>}
import com.evolutiongaming.akkaeffect.Conversion.implicits._

trait Receive[F[_], A, B] {
  import Receive._

  def apply(a: A, reply: Reply[F, B]): F[Stop]
}

object Receive {

  type Stop = Boolean


  def empty[F[_] : Applicative, A, B]: Receive[F, A, B] = const(false.pure[F])

  def stop[F[_] : Applicative, A, B]: Receive[F, A, B] = const(true.pure[F])

  def const[F[_] : Applicative, A, B](stop: F[Stop]): Receive[F, A, B] = (_: A, _: Reply[F, B]) => stop


  implicit class ReceiveOps[F[_], A, B](val self: Receive[F, A, B]) extends AnyVal {

    def mapK[G[_]](to: F ~> G, from: G ~> F): Receive[G, A, B] = new Receive[G, A, B] {

      def apply(msg: A, reply: Reply[G, B]) = {
        to(self(msg, reply.mapK(from)))
      }
    }


    def mapA[AA](f: AA => F[Option[A]])(implicit F: Monad[F]): Receive[F, AA, B] = new Receive[F, AA, B] {

      def apply(msg: AA, reply: Reply[F, B]) = {
        for {
          msg  <- f(msg)
          stop <- msg.fold(false.pure[F]) { msg => self(msg, reply) }
        } yield stop
      }
    }


    def untyped(implicit F: FlatMap[F], anyToA: Conversion[F, Any, A]): Receive[F, Any, Any] = {
      (a: Any, reply: Reply[F, Any]) => {
        for {
          a    <- a.convert[F, A]
          stop <- self(a, reply)
        } yield stop
      }
    }
  }
}