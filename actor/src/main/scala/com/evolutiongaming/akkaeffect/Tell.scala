package com.evolutiongaming.akkaeffect

import akka.actor.ActorRef
import cats.effect.Sync
import cats.implicits._
import cats.{Applicative, ~>}

trait Tell[F[_], -A] {

  def apply(a: A, sender: Option[ActorRef] = None): F[Unit]
}

object Tell {

  def empty[F[_] : Applicative, A]: Tell[F, A] = const(().pure[F])


  def const[F[_], A](unit: F[Unit]): Tell[F, A] = new Tell[F, A] {

    def apply(a: A, sender: Option[ActorRef]) = unit
  }


  def fromActorRef[F[_] : Sync](actorRef: ActorRef): Tell[F, Any] = new Tell[F, Any] {

    def apply(a: Any, sender: Option[ActorRef]) = {
      val sender1 = sender getOrElse ActorRef.noSender
      Sync[F].delay { actorRef.tell(a, sender1) }
    }

    override def toString = {
      val path = actorRef.path
      s"Tell($path)"
    }
  }


  implicit class TellOps[F[_], A](val self: Tell[F, A]) extends AnyVal {

    def mapK[G[_]](f: F ~> G): Tell[G, A] = new Tell[G, A] {

      def apply(a: A, sender: Option[ActorRef]) = f(self(a, sender))

      override def toString = self.toString
    }


    def imap[B](f: B => A): Tell[F, B] = new Tell[F, B] {

      def apply(a: B, sender: Option[ActorRef]) = self(f(a), sender)
    }


    def narrow[B <: A]: Tell[F, B] = self
  }
}