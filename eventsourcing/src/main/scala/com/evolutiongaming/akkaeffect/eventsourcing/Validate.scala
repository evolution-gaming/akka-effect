package com.evolutiongaming.akkaeffect.eventsourcing

import cats.implicits._
import cats.{Applicative, FlatMap}
import com.evolutiongaming.akkaeffect.persistence.SeqNr

/**
  * Describes "Validation" phase against actual state
  *
  * @tparam S state
  * @tparam E event
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

    def convertDirective[E1, B](
      f: Directive[F, S, E, A] => F[Directive[F, S, E1, B]])(implicit
      F: FlatMap[F]
    ): Validate[F, S, E1, B] = {

      (state, seqNr) => {
        for {
          a <- self(state, seqNr)
          a <- f(a)
        } yield a
      }
    }
  }
}