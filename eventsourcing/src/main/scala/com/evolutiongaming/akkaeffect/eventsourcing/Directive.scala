package com.evolutiongaming.akkaeffect.eventsourcing

import cats.implicits._
import cats.{Applicative, Monad}
import com.evolutiongaming.akkaeffect.persistence.{Events, SeqNr}

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

  def stop[F[_]: Applicative, S, E, A](f: Either[Throwable, SeqNr] => F[A]): Directive[F, S, E, A] = {
    Directive(none[Change[S, E]], Effect(f), stop = true)
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


  def change[F[_], S, E, A](change: Change[S, E])(f: Either[Throwable, SeqNr] => F[A]): Directive[F, S, E, A] = {
    Directive(change.some, Effect(f), stop = false)
  }

  def change[F[_], S, E, A](state: S, events: Events[E])(f: Either[Throwable, SeqNr] => F[A]): Directive[F, S, E, A] = {
    Directive(Change(state, events).some, Effect(f), stop = false)
  }


  implicit class DirectiveOps[F[_], S, E, A](val self: Directive[F, S, E, A]) extends AnyVal {

    def andThen(effect: Effect[F, A])(implicit F: Monad[F]): Directive[F, S, E, A] = {
      self.copy(effect = self.effect.productR(effect))
    }
  }
}
