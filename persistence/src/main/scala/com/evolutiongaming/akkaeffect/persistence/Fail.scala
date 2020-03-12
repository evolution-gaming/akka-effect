package com.evolutiongaming.akkaeffect.persistence

import cats.implicits._

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
}