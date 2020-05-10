package com.evolutiongaming.akkaeffect

import cats.effect.Sync
import cats.implicits._
import com.evolutiongaming.catshelper.CatsHelper._
import com.evolutiongaming.catshelper.{FromFuture, ToFuture}

import scala.concurrent.Future

object AkkaEffectHelper {

  implicit class IdOpsAkkaEffectHelper[A](val self: A) extends AnyVal {

      def asFuture: Future[A] = Future.successful(self)
  }


  implicit class OpsAkkaEffectHelper[F[_], A](val self: F[A]) extends AnyVal {

    /**
      * Unlike `Concurrent.start`, `startNow` tries to evaluate effect on current thread, unless it is asynchronous
      *
      * @return outer F[_] is about launching effect, inner F[_] is about effect completed
      */
    def startNow(implicit F: Sync[F], toFuture: ToFuture[F], fromFuture: FromFuture[F]): F[F[A]] = {
      Sync[F]
        .delay { self.toFuture }
        .map { future => fromFuture(future) }
    }
  }
}
