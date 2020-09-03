package com.evolutiongaming.akkaeffect

import cats.data.OptionT
import cats.syntax.all._
import cats.{Applicative, Functor, Monad}

import scala.reflect.ClassTag


trait Extract[F[_], A] {

  def apply(a: Any): OptionT[F, A]
}


object Extract {

  implicit def functorExtract[F[_]: Functor]: Functor[Extract[F, *]] = new Functor[Extract[F, *]] {
    def map[A, B](fa: Extract[F, A])(f: A => B) = a => fa(a).map(f)
  }


  def id[F[_]: Applicative]: Extract[F, Any] = a => a.some.toOptionT


  def apply[F[_], A](f: Any => OptionT[F, A]): Extract[F, A] = a => f(a)


  def summon[F[_], A](implicit F: Extract[F, A]): Extract[F, A] = F


  def fromClassTag[F[_]: Applicative, A: ClassTag]: Extract[F, A] = {
    fromPartialFunction { case a: A => a }
  }


  def fromPartialFunction[F[_]: Applicative, A](pf: PartialFunction[Any, A]): Extract[F, A] = {
    a => pf.lift(a).toOptionT[F]
  }


  def either[F[_]: Monad, L, R](implicit l: Extract[F, L], r: Extract[F, R]): Extract[F, Either[L, R]] = {
    case Right(a) => r(a).map { _.asRight[L] }
    case Left(a)  => l(a).map { _.asLeft[R] }
    case _        => OptionT.none
  }


  implicit class ExtractOps[F[_], A](val self: Extract[F, A]) extends AnyVal {

    def orElse(extract: Extract[F, A])(implicit F: Monad[F]): Extract[F, A] = a => self(a) orElse extract(a)
  }
}