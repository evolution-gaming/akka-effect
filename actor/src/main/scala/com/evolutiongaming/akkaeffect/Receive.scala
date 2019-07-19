package com.evolutiongaming.akkaeffect

import cats.implicits._
import cats.{Monad, ~>}

trait Receive[F[_], A, B] {

  type Stop = Receive.Stop


  def apply(a: A, reply: Reply[F, B]): F[Stop]

  /**
    * Called if stop was triggered externally only
    */
  def postStop: F[Unit]
}

object Receive {

  type Any[F[_]] = Receive[F, scala.Any, scala.Any]

  type Stop = Boolean


  implicit class ReceiveOps[F[_], A, B](val self: Receive[F, A, B]) extends AnyVal {

    def mapK[G[_]](to: F ~> G, from: G ~> F): Receive[G, A, B] = new Receive[G, A, B] {

      def apply(msg: A, reply: Reply[G, B]) = {
        to(self(msg, reply.mapK(from)))
      }

      def postStop = to(self.postStop)
    }


    def mapA[C](f: C => F[Option[A]])(implicit F: Monad[F]): Receive[F, C, B] = new Receive[F, C, B] {

      def apply(msg: C, reply: Reply[F, B]) = {
        for {
          msg  <- f(msg)
          stop <- msg.fold(false.pure[F]) { msg => self(msg, reply) }
        } yield stop
      }

      def postStop = self.postStop
    }
  }
}