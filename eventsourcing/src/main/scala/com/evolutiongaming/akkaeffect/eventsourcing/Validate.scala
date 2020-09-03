package com.evolutiongaming.akkaeffect.eventsourcing

import cats.implicits._
import cats.{Applicative, FlatMap, Monad}
import com.evolutiongaming.akkaeffect.persistence.SeqNr

/**
  * Describes "Validation" phase against actual state
  *
  * @tparam S state
  * @tparam E event
  * @tparam A result
  */
trait Validate[F[_], S, E, A] {

  def apply(state: S, seqNr: SeqNr): F[Directive[F, S, E, A]]
}

object Validate {

  def const[F[_], S, E, A](directive: F[Directive[F, S, E, A]]): Validate[F, S, E, A] = (_, _) => directive


  def empty[F[_]: Applicative, S, E]: Validate[F, S, E, Unit] = const(Directive.empty[F, S, E].pure[F])


  def apply[S]: Apply[S] = new Apply[S]

  private[Validate] final class Apply[S](private val b: Boolean = true) extends AnyVal {

    def apply[F[_], E, A](f: (S, SeqNr) => F[Directive[F, S, E, A]]): Validate[F, S, E, A] = {
      (state, seqNr) => f(state, seqNr)
    }
  }


  def effect[S, E]: EffectApply[S, E] = new EffectApply[S, E]

  private[Validate] final class EffectApply[S, E](private val b: Boolean = true) extends AnyVal {

    def apply[F[_]: Applicative, A](f: Either[Throwable, SeqNr] => F[A]): Validate[F, S, E, A] = {
      const(Directive.effect[S, E](f).pure[F])
    }
  }


  implicit class ValidateOps[F[_], S, E, A](val self: Validate[F, S, E, A]) extends AnyVal {

    def map[E1, A1](
      f: Directive[F, S, E, A] => Directive[F, S, E1, A1])(implicit
      F: FlatMap[F]
    ): Validate[F, S, E1, A1] = {
      (state, seqNr) => self(state, seqNr).map(f)
    }

    def mapM[E1, A1](
      f: Directive[F, S, E, A] => F[Directive[F, S, E1, A1]])(implicit
      F: FlatMap[F]
    ): Validate[F, S, E1, A1] = {
      (state, seqNr) => self(state, seqNr).flatMap(f)
    }

    def convert[S1, E1, A1](
      sf: S => F[S1],
      s1f: S1 => F[S],
      ef: E => F[E1],
      af: A => F[A1])(implicit
      F: Monad[F]
    ): Validate[F, S1, E1, A1] = {
      (state, seqNr) => {
        for {
          a <- s1f(state)
          a <- self(a, seqNr)
          a <- a.convert(sf, ef, af)
        } yield a
      }
    }

    def convertE[E1](f: E => F[E1])(implicit F: Monad[F]): Validate[F, S, E1, A] = {
      self.mapM { _.convertE(f) }
    }

    def convertS[S1](
      sf: S => F[S1],
      s1f: S1 => F[S])(implicit
      F: Monad[F]
    ): Validate[F, S1, E, A] = {
      (state, seqNr) => {
        for {
          a <- s1f(state)
          a <- self(a, seqNr)
          a <- a.convertS(sf)
        } yield a
      }
    }
  }
}