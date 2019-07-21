package com.evolutiongaming.akkaeffect

import akka.actor.ActorRef
import cats.effect.Sync
import cats.implicits._
import cats.{Applicative, ~>}

trait Reply[F[_], -A] {

  def apply(a: A): F[Unit]
}

object Reply {

  def empty[F[_] : Applicative, A]: Reply[F, A] = const(().pure[F])


  def const[F[_], A](unit: F[Unit]): Reply[F, A] = new Reply[F, A] {

    def apply(a: A) = unit
  }


  def apply[F[_] : Sync](
    to: ActorRef,
    from: Option[ActorRef],
  ): Reply[F, Any] = new Reply[F, Any] {

    def apply(a: Any): F[Unit] = {
      Sync[F].delay { to.tell(a, from.orNull) }
    }

    override def toString = {

      def str(actorRef: ActorRef) = actorRef.path.toString

      val fromStr = from.fold("")(from => s", ${ str(from) }")
      val stStr = str(to)
      s"Reply($stStr$fromStr)"
    }
  }


  implicit class ReplyOps[F[_], A](val self: Reply[F, A]) extends AnyVal {

    def mapK[G[_]](f: F ~> G): Reply[G, A] = new Reply[G, A] {

      def apply(a: A) = f(self(a))

      override def toString = self.toString
    }


    def narrow[B <: A]: Reply[F, B] = self
  }
}