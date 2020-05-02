package com.evolutiongaming.akkaeffect.persistence

import cats.Applicative
import cats.implicits._

trait Replay[F[_], E] {

  def apply(seqNr: SeqNr, event: E): F[Unit]
}

object Replay {

  def apply[F[_], E](f: (SeqNr, E) => F[Unit]): Replay[F, E] = (seqNr, event) => f(seqNr, event)

  def const[F[_], E](value: F[Unit]): Replay[F, E] = (_, _) => value

  def empty[F[_]: Applicative, E]: Replay[F, E] = const(().pure[F])
}