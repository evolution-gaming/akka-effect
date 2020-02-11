package com.evolutiongaming.akkaeffect

final case class Releasable[F[_], A](value: A, release: Option[F[Unit]] = None)