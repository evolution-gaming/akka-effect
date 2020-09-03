package com.evolutiongaming.akkaeffect.eventsourcing

import cats.Monad
import cats.syntax.all._
import com.evolutiongaming.akkaeffect.persistence.SeqNr

/**
  * @see [[com.evolutiongaming.akkaeffect.persistence.Replay]]
  * @tparam S state
  * @tparam E event
  */
trait Replay[F[_], S, E] {

  def apply(state: S, event: E, seqNr: SeqNr): F[S]
}

object Replay {

  def apply[S, E]: Apply[S, E] = new Apply[S, E]

  private[Replay] final class Apply[S, E](private val b: Boolean = true) extends AnyVal {

    def apply[F[_]](f: (S, E, SeqNr) => F[S]): Replay[F, S, E] = {
      (state, event, seqNr) => f(state, event, seqNr)
    }
  }

  implicit class ReplayOps[F[_], S, E](val self: Replay[F, S, E]) extends AnyVal {

    def convert[E1](f: E1 => F[E])(implicit F: Monad[F]): Replay[F, S, E1] = {
      (state, event, seqNr) =>
        f(event).flatMap { event => self(state, event, seqNr) }
    }
  }
}