package com.evolutiongaming.akkaeffect.persistence

import cats.implicits._
import cats.{Applicative, FlatMap}

/**
  * Used during recovery to replay events against passed state
  *
  * @tparam S state
  * @tparam E event
  */
trait Replay[F[_], S, E] {

  def apply(seqNr: SeqNr, state: S, event: E): F[S]
}

object Replay {

  def const[F[_], S, E](state: F[S]): Replay[F, S, E] = (_, _, _) => state

  def empty[F[_] : Applicative, S, E]: Replay[F, S, E] = (_, state, _) => state.pure[F]


  def apply[F[_], S, E](f: (SeqNr, S, E) => F[S]): Replay[F, S, E] = {
    (seqNr, state, event) => f(seqNr, state, event)
  }


  implicit class ReplayOps[F[_], S, E](val self: Replay[F, S, E]) extends AnyVal {

    def convert[S1, E1](
      sf: S => F[S1],
      s1f: S1 => F[S],
      ef: E1 => F[E])(implicit
      F: FlatMap[F]
    ): Replay[F, S1, E1] = {
      (seqNr, state, event) => {
        for {
          s <- s1f(state)
          e <- ef(event)
          s <- self(seqNr, s, e)
          s <- sf(s)
        } yield s
      }
    }


    def widen[S1 >: S, E1 >: E](sf: S1 => F[S], ef: E1 => F[E])(implicit F: FlatMap[F]): Replay[F, S1, E1] = {
      (seqNr, state, event) =>
        for {
          s <- sf(state)
          e <- ef(event)
          s <- self(seqNr, s, e)
        } yield s
    }
    

    def typeless(sf: Any => F[S], ef: Any => F[E])(implicit F: FlatMap[F]): Replay[F, Any, Any] = widen(sf, ef)
  }
}