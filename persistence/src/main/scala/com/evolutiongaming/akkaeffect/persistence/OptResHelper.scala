package com.evolutiongaming.akkaeffect.persistence

import cats.effect.Resource

object OptResHelper {

  implicit class ResourceOptionOpsOptResHelper[F[_], A](val self: Resource[F, Option[A]]) extends AnyVal {

    def toOptRes: OptRes[F, A] = OptRes(self)
  }
}