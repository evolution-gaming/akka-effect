package com.evolutiongaming.akkaeffect

import cats.data.OptionT
import cats.effect.Resource

package object persistence {

  type SeqNr = Long

  type Timestamp = Long


  type OptRes[F[_], A] = OptionT[Resource[F, *], A]

  object OptRes {
    def apply[F[_], A](value: Resource[F, Option[A]]): OptRes[F, A] = OptionT(value)
  }
}