package com.evolutiongaming.akkaeffect

import cats.FlatMap
import cats.implicits._
import com.evolutiongaming.catshelper.CatsHelper._
import com.evolutiongaming.catshelper.{FromFuture, ToFuture}

import scala.concurrent.Future

trait StateVar[F[_], A] {

  def update(f: A => F[A]): Unit
}

object StateVar {

  def apply[F[_]](implicit F: FlatMap[F]): ApplyBuilders[F] = new ApplyBuilders(F)

  def of[F[_] : FlatMap : ToFuture : FromFuture, A](initial: A): StateVar[F, A] = {

    var state = Future.successful(initial)

    (f: A => F[A]) => {
      state = FromFuture[F]
        .apply { state }
        .flatMap(f)
        .toFuture
    }
  }


  final class ApplyBuilders[F[_]](val F: FlatMap[F]) extends AnyVal {

    def of[A](a: A)(implicit toFuture: ToFuture[F], fromFuture: FromFuture[F]): StateVar[F, A] = {
      StateVar.of[F, A](a)(F, toFuture, fromFuture)
    }
  }
}
