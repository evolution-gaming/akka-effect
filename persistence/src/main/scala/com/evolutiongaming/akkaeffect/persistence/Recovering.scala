package com.evolutiongaming.akkaeffect.persistence

import cats.Monad
import cats.effect.Resource
import cats.implicits.catsSyntaxApplicativeId
import com.evolutiongaming.akkaeffect.{Envelope, Receive}

import scala.annotation.nowarn

/** Describes "Recovery" phase
  *
  * @tparam S
  *   snapshot
  * @tparam E
  *   event
  * @tparam A
  *   recovery result
  */
trait Recovering[F[_], S, E, +A] {

  /** Used to replay events during recovery against passed state, resource will be released when recovery is completed
    */
  def replay: Resource[F, Replay[F, E]]

  /** Called when recovery completed, resource will be released upon actor termination
    *
    * @see
    *   [[akka.persistence.RecoveryCompleted]]
    */
  @deprecated("Use completed with RecoveryContext", "4.1.5")
  def completed(
    seqNr: SeqNr,
    journaller: Journaller[F, E],
    snapshotter: Snapshotter[F, S],
  ): Resource[F, A]

  /** Called when recovery completed, resource will be released upon actor termination
    *
    * @see
    *   [[akka.persistence.RecoveryCompleted]]
    */
  def completed(context: Recovering.RecoveryContext[F, S, E]): Resource[F, A] = {
    @nowarn("msg=deprecated")
    val a = completed(context.seqNr, context.journaller, context.snapshotter)
    a
  }

}

object Recovering {

  /** Context containing information about recovery and provides access to journaller and snapshotter
    */
  trait RecoveryContext[F[_], -S, -E] {
    def seqNr: SeqNr
    def journaller: Journaller[F, E]
    def snapshotter: Snapshotter[F, S]
    def recoveredFromPersistence: Boolean
  }
  object RecoveryContext {

    private case class Impl[F[_], S, E](
      seqNr: SeqNr,
      journaller: Journaller[F, E],
      snapshotter: Snapshotter[F, S],
      recoveredFromPersistence: Boolean,
    ) extends RecoveryContext[F, S, E]

    def apply[F[_], S, E](
      seqNr: SeqNr,
      journaller: Journaller[F, E],
      snapshotter: Snapshotter[F, S],
      recoveredFromPersistence: Boolean = true,
    ): RecoveryContext[F, S, E] = Impl(seqNr, journaller, snapshotter, recoveredFromPersistence)

  }

  def apply[S]: Apply[S] = new Apply[S]

  final private[Recovering] class Apply[S](private val b: Boolean = true) extends AnyVal {

    def apply[F[_], E, A](replay: Resource[F, Replay[F, E]])(
      completed: (SeqNr, Journaller[F, E], Snapshotter[F, S]) => Resource[F, A],
    ): Recovering[F, S, E, A] = {
      val replay1    = replay
      val completed1 = completed
      new Recovering[F, S, E, A] {

        def replay = replay1

        def completed(
          seqNr: SeqNr,
          journaller: Journaller[F, E],
          snapshotter: Snapshotter[F, S],
        ) =
          completed1(seqNr, journaller, snapshotter)
      }
    }
  }

  def const[S]: Const[S] = new Const[S]

  final private[Recovering] class Const[S](private val b: Boolean = true) extends AnyVal {

    def apply[F[_], E, A](replay: Resource[F, Replay[F, E]])(
      completed: Resource[F, A],
    ): Recovering[F, S, E, A] = {
      val replay1    = replay
      val completed1 = completed
      new Recovering[F, S, E, A] {

        def replay = replay1

        def completed(seqNr: SeqNr, journaller: Journaller[F, E], snapshotter: Snapshotter[F, S]) =
          completed1
      }
    }
  }

  implicit class RecoveringOps[F[_], S, E, A](val self: Recovering[F, S, E, A]) extends AnyVal {

    def convert[S1, E1, A1](sf: S => F[S1], ef: E => F[E1], e1f: E1 => F[E], af: A => Resource[F, A1])(implicit
      F: Monad[F],
    ): Recovering[F, S1, E1, A1] = new Recovering[F, S1, E1, A1] {

      def replay = self.replay.map(_.convert(e1f))

      def completed(
        seqNr: SeqNr,
        journaller: Journaller[F, E1],
        snapshotter: Snapshotter[F, S1],
      ) = {
        val journaller1  = journaller.convert(ef)
        val snapshotter1 = snapshotter.convert(sf)
        val context1     = RecoveryContext(seqNr, journaller1, snapshotter1)
        self.completed(context1).flatMap(af)
      }
    }

    def map[A1](f: A => A1): Recovering[F, S, E, A1] = new Recovering[F, S, E, A1] {

      def replay = self.replay

      def completed(
        seqNr: SeqNr,
        journaller: Journaller[F, E],
        snapshotter: Snapshotter[F, S],
      ) = {
        val context = RecoveryContext(seqNr, journaller, snapshotter)
        self.completed(context).map(f)
      }
    }

    def mapM[A1](
      f: A => Resource[F, A1],
    ): Recovering[F, S, E, A1] = new Recovering[F, S, E, A1] {

      def replay = self.replay

      def completed(
        seqNr: SeqNr,
        journaller: Journaller[F, E],
        snapshotter: Snapshotter[F, S],
      ) = {
        val context = RecoveryContext(seqNr, journaller, snapshotter)
        self.completed(context).flatMap(f)
      }
    }
  }

  implicit class RecoveringReceiveEnvelopeOps[F[_], S, E, C](
    val self: Recovering[F, S, E, Receive[F, Envelope[C], Boolean]],
  ) extends AnyVal {

    def widen[S1 >: S, C1 >: C, E1 >: E](ef: E1 => F[E], cf: C1 => F[C])(implicit
      F: Monad[F],
    ): Recovering[F, S1, E1, Receive[F, Envelope[C1], Boolean]] =
      new Recovering[F, S1, E1, Receive[F, Envelope[C1], Boolean]] {

        def replay = self.replay.map(_.convert(ef))

        def completed(
          seqNr: SeqNr,
          journaller: Journaller[F, E1],
          snapshotter: Snapshotter[F, S1],
        ) = {
          val context = RecoveryContext(seqNr, journaller, snapshotter)
          self.completed(context).map(_.convert(cf, _.pure[F]))
        }
      }

    def typeless(ef: Any => F[E], cf: Any => F[C])(implicit
      F: Monad[F],
    ): Recovering[F, Any, Any, Receive[F, Envelope[Any], Boolean]] =
      widen(ef, cf)
  }
}
