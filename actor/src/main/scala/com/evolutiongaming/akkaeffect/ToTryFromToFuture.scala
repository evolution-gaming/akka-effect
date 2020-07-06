package com.evolutiongaming.akkaeffect

import com.evolutiongaming.catshelper.CatsHelper._
import com.evolutiongaming.catshelper.{ToFuture, ToTry}

import scala.util.Failure

object ToTryFromToFuture {

  def syncOrError[F[_]: ToFuture]: ToTry[F] = new ToTry[F] {

    def apply[A](fa: F[A]) = {
      fa
        .toFuture
        .value
        .getOrElse { Failure(new AsyncEffectError) }
    }
  }
}

final class AsyncEffectError extends RuntimeException
