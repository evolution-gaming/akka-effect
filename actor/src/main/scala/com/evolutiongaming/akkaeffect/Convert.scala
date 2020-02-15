package com.evolutiongaming.akkaeffect

import cats.Applicative
import cats.effect.Sync
import cats.implicits._

import scala.reflect.ClassTag

trait Convert[F[_], -A, B] {

  def apply(a: A): F[B]
}


object Convert {

  def apply[F[_], A, B](implicit F: Convert[F, A, B]): Convert[F, A, B] = F


  implicit def identityConversion[F[_] : Applicative, A]: Convert[F, A, A] = _.pure[F]


  object implicits {

    implicit class IdOpsConvert[A](val self: A) extends AnyVal {

      def convert[F[_], B](implicit F: Convert[F, A, B]): F[B] = F(self)
    }
  }
}
