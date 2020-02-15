package com.evolutiongaming.akkaeffect

import cats.implicits._
import com.evolutiongaming.catshelper.ApplicativeThrowable

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
}
