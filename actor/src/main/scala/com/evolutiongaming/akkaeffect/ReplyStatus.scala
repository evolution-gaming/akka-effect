package com.evolutiongaming.akkaeffect

import akka.actor.Status.Status
import akka.actor.{ActorRef, Status}
import cats.effect.Sync
import cats.syntax.all._
import cats.{Contravariant, FlatMap, ~>}

/**
  * Typesafe api for replying part of "ask pattern" in conjunction with `Status`
  *
  * @see [[akka.actor.Status]]
  * @tparam A reply
  */
trait ReplyStatus[F[_], -A] {

  def success(a: A): F[Unit]

  def fail(error: Throwable): F[Unit]
}

object ReplyStatus {

  implicit def contravariantReplyStatus[F[_]]: Contravariant[ReplyStatus[F, *]] = {
    new Contravariant[ReplyStatus[F, *]] {

      def contramap[A, B](fa: ReplyStatus[F, A])(f: B => A) = new ReplyStatus[F, B] {

        def success(a: B): F[Unit] = fa.success(f(a))

        def fail(error: Throwable) = fa.fail(error)
      }
    }
  }

  def fromReply[F[_]](reply: Reply[F, Status]): ReplyStatus[F, Any] = new ReplyStatus[F, Any] {

    def success(a: Any) = reply(Status.Success(a))

    def fail(error: Throwable) = reply(Status.Failure(error))

    override def toString = reply.toString
  }


  def fromActorRef[F[_]: Sync](
    to: ActorRef,
    from: Option[ActorRef],
  ): ReplyStatus[F, Any] = {
    Reply
      .fromActorRef(to, from)
      .toReplyStatus
  }


  implicit class ReplyStatusOps[F[_], A](val self: ReplyStatus[F, A]) extends AnyVal {

    def mapK[G[_]](f: F ~> G): ReplyStatus[G, A] = new ReplyStatus[G, A] {

      def success(a: A) = f(self.success(a))

      def fail(error: Throwable) = f(self.fail(error))

      override def toString = self.toString
    }


    def convert[B](f: B => F[A])(implicit F: FlatMap[F]): ReplyStatus[F, B] = new ReplyStatus[F, B] {

      def success(a: B) = {
        for {
          a <- f(a)
          a <- self.success(a)
        } yield a
      }

      def fail(error: Throwable) = self.fail(error)
    }


    def narrow[B <: A]: ReplyStatus[F, B] = self
  }
}
