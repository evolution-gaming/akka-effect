package com.evolutiongaming.akkaeffect

import akka.actor.ActorRef
import cats.effect.{Resource, Sync}
import cats.implicits._
import cats.{Applicative, FlatMap, Monad, ~>}

/**
  * Typesafe api for Actor.receive
  *
  * @see [[akka.actor.Actor.receive]]
  * @tparam A message
  * @tparam B reply
  */
trait Receive1[F[_], -A, B] {
  import Receive1._

  /**
    * Called strictly sequentially, next message will be processed only after we've done with the previous one
    * This basically preserves the semantic of Actors
    */
  def apply(msg: A, reply: Reply[F, B]): F[Stop]
}

object Receive1 {

  type Stop = Boolean


  def empty[F[_]: Applicative, A, B]: Receive1[F, A, B] = const(false.pure[F])

  def stop[F[_]: Applicative, A, B]: Receive1[F, A, B] = const(true.pure[F])

  def const[F[_]: Applicative, A, B](stop: F[Stop]): Receive1[F, A, B] = (_, _) => stop

  def apply[F[_], A, B](f: (A, Reply[F, B]) => F[Stop]): Receive1[F, A, B] = {
    (msg, reply) => f(msg, reply)
  }


  implicit class ReceiveOps[F[_], A, B](val self: Receive1[F, A, B]) extends AnyVal {

    def mapK[G[_]](fg: F ~> G, gf: G ~> F): Receive1[G, A, B] = {
      (msg: A, reply: Reply[G, B]) => {
        fg(self(msg, reply.mapK(gf)))
      }
    }


    def collect[AA](f: AA => F[Option[A]])(implicit F: Monad[F]): Receive1[F, AA, B] = {
      (msg, reply) => {
        for {
          msg  <- f(msg)
          stop <- msg.fold(false.pure[F]) { msg => self(msg, reply) }
        } yield stop
      }
    }


    def convert[A1, B1](
      af: A1 => F[A],
      bf: B => F[B1])(implicit
      F: FlatMap[F],
    ): Receive1[F, A1, B1] = {
      (msg, reply) => {
        for {
          msg  <- af(msg)
          stop <- self(msg, reply.convert(bf))
        } yield stop
      }
    }


    def convertMsg[A1](f: A1 => F[A])(implicit F: FlatMap[F]): Receive1[F, A1, B] = {
      (msg, reply) => {
        for {
          msg  <- f(msg)
          stop <- self(msg, reply)
        } yield stop
      }
    }


    def widen[A1 >: A, B1 >: B](f: A1 => F[A])(implicit F: FlatMap[F]): Receive1[F, A1, B1] = {
      (msg, reply) => {
        for {
          msg  <- f(msg)
          stop <- self(msg, reply)
        } yield stop
      }
    }


    def typeless(f: Any => F[A])(implicit F: FlatMap[F]): Receive1[F, Any, Any] = widen(f)
  }


  implicit class ReceiveAnyOps[F[_]](val self: Receive1[F, Any, Any]) extends AnyVal {

    def toReceiveAny(actorRef: ActorRef)(implicit F: Sync[F]): Receive[F, Any] = {
      Receive.fromReceive(self, actorRef)
    }
  }
}