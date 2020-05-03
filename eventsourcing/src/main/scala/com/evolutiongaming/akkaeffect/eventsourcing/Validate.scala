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


  def apply[F[_], S, E, A](f: (S, SeqNr) => F[Directive[F, S, E, A]]): Validate[F, S, E, A] = {
    (state, seqNr) => f(state, seqNr)
  }


  def effect[F[_]: Applicative, S, E, R](f: Either[Throwable, SeqNr] => F[R]): Validate[F, S, E, R] = {
    const(Directive.effect[F, S, E, R](f).pure[F])
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