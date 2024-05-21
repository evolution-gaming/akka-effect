package com.evolutiongaming.akkaeffect

import akka.actor.ActorRef
import cats.effect.Sync
import cats.syntax.all.*
import cats.{Applicative, Contravariant, FlatMap, ~>}

/** Typesafe api for ActorRef.tell
  *
  * @see
  *   [[akka.actor.ActorRef.tell]]
  * @tparam A
  *   message
  */
trait Tell[F[_], -A] {

  def apply(msg: A, sender: Option[ActorRef] = None): F[Unit]
}

object Tell {

  implicit def contravariantTell[F[_]]: Contravariant[Tell[F, *]] = new Contravariant[Tell[F, *]] {

    def contramap[A, B](fa: Tell[F, A])(f: B => A) = { (b: B, sender: Option[ActorRef]) =>
      fa(f(b), sender)
    }
  }

  def empty[F[_]: Applicative, A]: Tell[F, A] = const(().pure[F])

  def const[F[_], A](unit: F[Unit]): Tell[F, A] = (_: A, _: Option[ActorRef]) => unit

  def fromActorRef[F[_]: Sync](actorRef: ActorRef): Tell[F, Any] = new Tell[F, Any] {

    def apply(msg: Any, sender: Option[ActorRef]) = {
      val sender1 = sender getOrElse ActorRef.noSender
      Sync[F].delay(actorRef.tell(msg, sender1))
    }

    override def toString = {
      val path = actorRef.path
      s"Tell($path)"
    }
  }

  implicit class TellOps[F[_], A](val self: Tell[F, A]) extends AnyVal {

    def mapK[G[_]](f: F ~> G): Tell[G, A] = new Tell[G, A] {

      def apply(msg: A, sender: Option[ActorRef]) = f(self(msg, sender))

      override def toString = self.toString
    }

    def narrow[B <: A]: Tell[F, B] = self

    def convert[B](f: B => F[A])(implicit F: FlatMap[F]): Tell[F, B] = { (a: B, sender: Option[ActorRef]) =>
      for {
        a <- f(a)
        a <- self(a, sender)
      } yield a
    }
  }
}
