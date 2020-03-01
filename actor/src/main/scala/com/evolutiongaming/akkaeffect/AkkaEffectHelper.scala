package com.evolutiongaming.akkaeffect

import cats.effect.Sync
import cats.implicits._
import com.evolutiongaming.catshelper.CatsHelper._
import com.evolutiongaming.catshelper.{ApplicativeThrowable, FromFuture, ToFuture}

import scala.reflect.ClassTag

object AkkaEffectHelper {

  implicit class IdOpsAkkaEffectHelper[A](val self: A) extends AnyVal {

    def cast[F[_] : ApplicativeThrowable, B <: A](implicit tag: ClassTag[B]): F[B] = {
      tag
        .unapply(self)
        .fold {
          val error = new ClassCastException(s"${ self.getClass.getName } cannot be cast to ${ tag.runtimeClass.getName }")
          error.raiseError[F, B]
        } { a =>
          a.pure[F]
        }
    }
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
