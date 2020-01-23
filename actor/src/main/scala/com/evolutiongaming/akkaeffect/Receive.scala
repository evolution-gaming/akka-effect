package com.evolutiongaming.akkaeffect

import cats.implicits._
import cats.{Applicative, FlatMap, Monad, ~>}
import com.evolutiongaming.akkaeffect.Conversion.implicits._

trait Receive[F[_], A, B] {

  type Stop = Receive.Stop


  def apply(a: A, reply: Reply[F, B]): F[Stop]

  /**
    * Called if stop was triggered externally only
    */
  def postStop: F[Unit] // TODO remove and switch to using Resource
}

object Receive {

  type Stop = Boolean


  def empty[F[_] : Applicative, A, B]: Receive[F, A, B] = new Receive[F, A, B] {

    def apply(a: A, reply: Reply[F, B]) = false.pure[F]

    def postStop = ().pure[F]
  }


  implicit class ReceiveOps[F[_], A, B](val self: Receive[F, A, B]) extends AnyVal {

    def mapK[G[_]](to: F ~> G, from: G ~> F): Receive[G, A, B] = new Receive[G, A, B] {

      def apply(msg: A, reply: Reply[G, B]) = {
        to(self(msg, reply.mapK(from)))
      }

      def postStop = to(self.postStop)
    }


    def mapA[AA](f: AA => F[Option[A]])(implicit F: Monad[F]): Receive[F, AA, B] = new Receive[F, AA, B] {

      def apply(msg: AA, reply: Reply[F, B]) = {
        for {
          msg  <- f(msg)
          stop <- msg.fold(false.pure[F]) { msg => self(msg, reply) }
        } yield stop
      }

      def postStop = self.postStop
    }


    def untype(implicit F: FlatMap[F], anyToA: Conversion[F, Any, A]): Receive[F, Any, Any] = {

      new Receive[F, Any, Any] {

        def apply(a: Any, reply: Reply[F, Any]) = {
          for {
            a    <- a.convert[F, A]
            stop <- self(a, reply)
          } yield stop
        }

        def postStop = self.postStop
      }
    }
  }
}