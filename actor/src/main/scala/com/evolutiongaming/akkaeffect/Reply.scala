package com.evolutiongaming.akkaeffect

import akka.actor.ActorRef
import cats.effect.Sync
import cats.implicits._
import cats.{Applicative, FlatMap, ~>}

trait Reply[F[_], -A] {

  def apply(msg: A): F[Unit]
  // TODO support fail call
}

object Reply {

  def empty[F[_] : Applicative, A]: Reply[F, A] = const(().pure[F])


  def const[F[_], A](unit: F[Unit]): Reply[F, A] = (_: A) => unit


  def fromActorRef[F[_] : Sync](
    to: ActorRef,
    from: Option[ActorRef],
  ): Reply[F, Any] = {
    new Reply[F, Any] {

      def apply(msg: Any): F[Unit] = {
        Sync[F].delay { to.tell(msg, from.orNull) }
      }

      override def toString = {

        def str(actorRef: ActorRef) = actorRef.path.toString

        val fromStr = from.fold("")(from => s", ${ str(from) }")
        val stStr = str(to)
        s"Reply($stStr$fromStr)"
      }
    }
  }


  implicit class ReplyOps[F[_], A](val self: Reply[F, A]) extends AnyVal {

    def mapK[G[_]](f: F ~> G): Reply[G, A] = new Reply[G, A] {

      def apply(msg: A) = f(self(msg))

      override def toString = self.toString
    }


    def narrow[B <: A]: Reply[F, B] = self


    def convert[B](f: B => F[A])(implicit F: FlatMap[F]): Reply[F, B] = {
      msg: B =>
        for {
          a <- f(msg)
          a <- self(a)
        } yield a
    }
  }
}