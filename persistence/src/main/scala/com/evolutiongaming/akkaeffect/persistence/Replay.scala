package com.evolutiongaming.akkaeffect.persistence

import cats.implicits._
import cats.{Applicative, FlatMap}

trait Replay[F[_], S, E] {

  def apply(state: S, event: E, seqNr: SeqNr): F[S]
}

object Replay {

  def const[F[_], S, E](state: F[S]): Replay[F, S, E] = (_: S, _: E, _: SeqNr) => state

  def empty[F[_] : Applicative, S, E]: Replay[F, S, E] = (state: S, _: E, _: SeqNr) => state.pure[F]


  implicit class ReplayOps[F[_], S, E](val self: Replay[F, S, E]) extends AnyVal {

    def convert[S1, E1](
      sf: S => F[S1],
      s1f: S1 => F[S],
      ef: E1 => F[E])(implicit
      F: FlatMap[F]
    ): Replay[F, S1, E1] = {
      (state: S1, event: E1, seqNr: SeqNr) => {
        for {
          s <- s1f(state)
          e <- ef(event)
          s <- self(s, e, seqNr)
          s <- sf(s)
        } yield s
      }
    }

    def map[S1, E1](
      sf: S => S1,
      s1f: S1 => S,
      ef: E1 => E)(implicit
      F: Applicative[F]
    ): Replay[F, S1, E1] = {
      (state: S1, event: E1, seqNr: SeqNr) =>
        for {
          s <- self(s1f(state), ef(event), seqNr)
        } yield {
          sf(s)
        }
    }

    def typeless(sf: Any => F[S], ef: Any => F[E])(implicit F: FlatMap[F]): Replay[F, Any, Any] = {
      (state: Any, event: Any, seqNr: SeqNr) =>
        for {
          s <- sf(state)
          e <- ef(event)
          s <- self(s, e, seqNr)
        } yield s
    }
  }
}