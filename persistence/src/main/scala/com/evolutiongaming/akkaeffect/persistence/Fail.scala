package com.evolutiongaming.akkaeffect.persistence

import cats.implicits._
import com.evolutiongaming.catshelper.MonadThrowable

trait Fail[F[_]] {

  def apply[A](msg: String, cause: Option[Throwable] = none): F[A]
}

object Fail {

  def apply[F[_]](implicit F: Fail[F]): Fail[F] = F

  object implicits {

    implicit class StringOpsFail(val self: String) extends AnyVal {

      def fail[F[_] : Fail, A]: F[A] = Fail[F].apply(self)
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