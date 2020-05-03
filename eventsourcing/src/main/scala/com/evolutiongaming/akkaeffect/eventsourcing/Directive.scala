package com.evolutiongaming.akkaeffect.eventsourcing

import cats.implicits._
import cats.{Applicative, Monad}
import com.evolutiongaming.akkaeffect.persistence.SeqNr

/**
  * Describes optional change as well as effect to be executed after change is applied and events are stored
  *
  * @param change - state and events
  * @param effect - will be executed after events are stored
  * @param stop   - to ensure that there will be no more changes
  * @tparam S state
  * @tparam E event
  */
final case class Directive[F[_], +S, +E, A](
  change: Option[Change[S, E]],
  effect: Effect[F, A],
  stop: Boolean)

object Directive {

  def empty[F[_]: Applicative, S, E]: Directive[F, S, E, Unit] = {
    Directive(none[Change[S, E]], Effect.empty[F], stop = false)
  }


  def stop[F[_]: Applicative, S, E, A]: Directive[F, S, E, Unit] = {
    Directive(none[Change[S, E]], Effect.empty[F], stop = true)
  }

  def stop[F[_]: Applicative, S, E, A](effect: Effect[F, A]): Directive[F, S, E, A] = {
    Directive(none[Change[S, E]], effect, stop = true)
  }


  def apply[F[_], S, E, A](effect: Effect[F, A]): Directive[F, S, E, A] = {
    apply(none, effect, stop = false)
  }

  def apply[F[_], S, E, A](change: Change[S, E], effect: Effect[F, A]): Directive[F, S, E, A] = {
    Directive(change.some, effect, stop = false)
  }


  def effect[F[_], S, E, A](f: Either[Throwable, SeqNr] => F[A]): Directive[F, S, E, A] = {
    Directive(Effect(f))
  }


  implicit class DirectiveOps[F[_], S, E, A](val self: Directive[F, S, E, A]) extends AnyVal {

    def andThen(effect: Effect[F, A])(implicit F: Monad[F]): Directive[F, S, E, A] = {
      self.copy(effect = self.effect.productR(effect))
    }
  }
}
