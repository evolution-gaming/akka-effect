package com.evolutiongaming.akkaeffect

import cats.Applicative
import cats.effect.Sync
import cats.implicits._

import scala.reflect.ClassTag

trait Conversion[F[_], -A, B] {

  def apply(a: A): F[B]
}


object Conversion {

  def apply[F[_], A, B](implicit F: Conversion[F, A, B]): Conversion[F, A, B] = F


  implicit def identityConversion[F[_] : Applicative, A]: Conversion[F, A, A] = _.pure[F]


  def cast[F[_] : Sync, A, B <: A](implicit tag: ClassTag[B]): Conversion[F, A, B] = {
    a: A => {
      tag.unapply(a) match {
        case Some(a) => a.pure[F]
        case None    => ClassCastError(a)(tag).raiseError[F, B]
      }
    }
  }


  object implicits {

    implicit class ConversionIdOps[A](val self: A) extends AnyVal {

      def convert[F[_], B](implicit F: Conversion[F, A, B]): F[B] = F(self)
    }
  }
}
