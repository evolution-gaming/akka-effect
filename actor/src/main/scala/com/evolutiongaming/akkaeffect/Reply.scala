package com.evolutiongaming.akkaeffect

import akka.actor.ActorRef
import akka.actor.Status.Status
import cats.effect.Sync
import cats.implicits._
import cats.{Applicative, Contravariant, FlatMap, ~>}

/**
  * Typesafe api for replying part of "ask pattern"
  *
  * @tparam A reply
  */
trait Reply[F[_], -A] {

  def apply(msg: A): F[Unit]
}

object Reply {

  def empty[F[_]: Applicative, A]: Reply[F, A] = const(().pure[F])

  def const[F[_], A](unit: F[Unit]): Reply[F, A] = _ => unit

  def apply[F[_], A](f: A => F[Unit]): Reply[F, A] = a => f(a)


  // TODO add the same for other classes
  implicit def contravariantReply[F[_]]: Contravariant[Reply[F, *]] = new Contravariant[Reply[F, *]] {

    def contramap[A, B](fa: Reply[F, A])(f: B => A) = msg => fa(f(msg))
  }

  def fromActorRef[F[_]: Sync](
    to: ActorRef,
    from: ActorRef,
  ): Reply[F, Any] = {
    fromActorRef(to = to, from = from.some)
  }

  def fromActorRef[F[_]: Sync](
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


    def convert[B](f: B => F[A])(implicit F: FlatMap[F]): Reply[F, B] = {
      msg: B =>
        for {
          a <- f(msg)
          a <- self(a)
        } yield a
    }


    def narrow[B <: A]: Reply[F, B] = self
  }


  implicit class ReplyAnyOps[F[_]](val self: Reply[F, Any]) extends AnyVal {

    def toReplyStatus: ReplyStatus[F, Any] = ReplyStatus.fromReply(self.narrow[Status])
  }
}