package com.evolutiongaming.akkaeffect

import akka.actor.ActorRef
import cats.implicits._
import com.evolutiongaming.catshelper.{ApplicativeThrowable, MonadThrowable}

trait Fail[F[_]] {

  def apply[A](msg: String, cause: Option[Throwable] = none): F[A]
}

object Fail {

  def summon[F[_]](implicit F: Fail[F]): Fail[F] = F


  def fromActorRef[F[_]: ApplicativeThrowable](actorRef: ActorRef): Fail[F] = new Fail[F] {

    def apply[A](msg: String, cause: Option[Throwable]) = {
      val path = actorRef.path.toStringWithoutAddress
      val causeStr: String = cause.foldMap { a => s": $a" }
      ActorError(s"$path $msg$causeStr", cause).raiseError[F, A]
    }
  }


  object implicits {

    implicit class StringOpsFail(val self: String) extends AnyVal {

      def fail[F[_]: Fail, A]: F[A] = Fail.summon[F].apply(self)

      def fail[F[_]: Fail, A](cause: Throwable): F[A] = Fail.summon[F].apply(self, cause.some)
    }
  }


  implicit class FailOps[F[_]](val self: Fail[F]) extends AnyVal {

    def adapt[B](msg: => String)(f: F[F[B]])(implicit F: MonadThrowable[F]): F[F[B]] = {

      def adapt[C](e: Throwable) = self[C](msg, e.some)

      f
        .handleErrorWith { e => adapt(e) }
        .map { _.handleErrorWith { e => adapt(e) } }
    }
  }
}