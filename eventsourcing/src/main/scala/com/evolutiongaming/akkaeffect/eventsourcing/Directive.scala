package com.evolutiongaming.akkaeffect.eventsourcing

import cats.syntax.all._
import cats.kernel.Semigroup
import cats.{Applicative, FlatMap, Functor, Monad}
import com.evolutiongaming.akkaeffect.persistence.{Events, SeqNr}

/** Describes optional change as well as effect to be executed after change is applied and events are stored
  *
  * @param change
  *   \- state and events
  * @param effect
  *   \- will be executed after events are stored
  * @param stop
  *   \- to ensure that there will be no more changes
  * @tparam S
  *   state
  * @tparam E
  *   event
  */
final case class Directive[F[_], +S, +E, A](change: Option[Change[S, E]], effect: Effect[F, A], stop: Boolean) {
  def mapState[S1](f: S => S1): Directive[F, S1, E, A] = copy(change = change.map(_.mapState(f)))

  def mapEvent[E1](f: E => E1): Directive[F, S, E1, A] = copy(change = change.map(_.mapEvent(f)))
}

object Directive {

  implicit def functorDirective[F[_]: Functor, S, E]: Functor[Directive[F, S, E, *]] =
    new Functor[Directive[F, S, E, *]] {
      def map[A, B](fa: Directive[F, S, E, A])(f: A => B) = fa.map(f)
    }

  implicit def semigroupDirective[F[_]: Monad, S, E, A: Semigroup]: Semigroup[Directive[F, S, E, A]] = { (a, b) =>
    Directive(a.change.combine(b.change), a.effect.combine(b.effect), a.stop || b.stop)
  }

  def empty[F[_]: Applicative, S, E]: Directive[F, S, E, Unit] =
    Directive(none[Change[S, E]], Effect.empty[F], stop = false)

  def stop[F[_]: Applicative, S, E]: Directive[F, S, E, Unit] =
    Directive(none[Change[S, E]], Effect.empty[F], stop = true)

  def stop[F[_], S, E, A](effect: Effect[F, A]): Directive[F, S, E, A] =
    Directive(none[Change[S, E]], effect, stop = true)

  def stop[F[_], S, E, A](f: Either[Throwable, SeqNr] => F[A]): Directive[F, S, E, A] =
    Directive(none[Change[S, E]], Effect(f), stop = true)

  def apply[F[_], S, E, A](effect: Effect[F, A]): Directive[F, S, E, A] =
    apply(none, effect, stop = false)

  def apply[F[_], S, E, A](change: Change[S, E], effect: Effect[F, A]): Directive[F, S, E, A] =
    Directive(change.some, effect, stop = false)

  def effect[S, E]: EffectApply[S, E] = new EffectApply[S, E]

  final private[Directive] class EffectApply[S, E](private val b: Boolean = true) extends AnyVal {

    def apply[F[_], A](f: Either[Throwable, SeqNr] => F[A]): Directive[F, S, E, A] =
      Directive(Effect(f))
  }

  def change[F[_], S, E, A](change: Change[S, E])(f: Either[Throwable, SeqNr] => F[A]): Directive[F, S, E, A] =
    Directive(change.some, Effect(f), stop = false)

  def change[F[_], S, E, A](state: S, events: Events[E])(f: Either[Throwable, SeqNr] => F[A]): Directive[F, S, E, A] =
    Directive(Change(state, events).some, Effect(f), stop = false)

  implicit class DirectiveOps[F[_], S, E, A](val self: Directive[F, S, E, A]) extends AnyVal {

    def mapEffect[A1](f: Effect[F, A] => Effect[F, A1]): Directive[F, S, E, A1] =
      self.copy(effect = f(self.effect))

    def map[A1](f: A => A1)(implicit F: Functor[F]): Directive[F, S, E, A1] =
      mapEffect(_.map(f))

    def mapM[A1](f: A => F[A1])(implicit F: FlatMap[F]): Directive[F, S, E, A1] =
      mapEffect(_.mapM(f))

    def andThen(effect: Effect[F, A])(implicit F: Monad[F]): Directive[F, S, E, A] =
      mapEffect(_.productR(effect))

    def convert[S1, E1, A1](sf: S => F[S1], ef: E => F[E1], af: A => F[A1])(implicit
      F: Monad[F]
    ): F[Directive[F, S1, E1, A1]] =
      self.change
        .traverse(_.convert(sf, ef))
        .map { change =>
          val effect = self.effect.mapM(af)
          self.copy(change, effect)
        }

    def convertE[E1](f: E => F[E1])(implicit F: Monad[F]): F[Directive[F, S, E1, A]] =
      self.change
        .traverse(_.convertE(f))
        .map(change => self.copy(change))

    def convertS[S1](f: S => F[S1])(implicit F: Monad[F]): F[Directive[F, S1, E, A]] =
      self.change
        .traverse(_.convertS(f))
        .map(change => self.copy(change))
  }
}
