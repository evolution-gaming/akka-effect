package com.evolutiongaming.akkaeffect.persistence

import cats.effect.Resource
import cats.implicits.catsSyntaxApplicativeId
import cats.{ApplicativeError, Monad}
import com.evolutiongaming.akkaeffect.{Envelope, Receive}

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
  def completed(
    seqNr: SeqNr,
    journaller: Journaller[F, E],
    snapshotter: Snapshotter[F, S],
  ): Resource[F, A]

  /** Called when state was transferred via state-transfer, resource will be released upon actor termination
    */
  def transferred(
    seqNr: SeqNr,
    journaller: Journaller[F, E],
    snapshotter: Snapshotter[F, S],
  ): Resource[F, A]

  /** Called when recovery failed
    *
    * @param journaller
    *   of type [[DeleteEventsTo]] because instance of [[Journaller]] is not available in this case. [[Journaller]] can
    *   be created based on known [[SeqNr]], while its now known in case of failure.
    */
  def failed(
    cause: Throwable,
    journaller: DeleteEventsTo[F],
    snapshotter: Snapshotter[F, S],
  ): Resource[F, Unit]

}

object Recovering {

  @deprecated(
    "This factory provided as direct replacement of Recovering[A](...)(...) and Recovering.const[A](...)(...), avoid using it as much as possible",
    "5.0.0",
  )
  def legacy[S]: Legacy[S] = new Legacy[S]

  final class Legacy[S](private val b: Boolean = true) extends AnyVal {

    /** This factory method is provided as direct replacement of removed Recovering[A](...)(...) and re-used `completed`
      * function for both `completed` and `transferred` cases. `failed` case re-throw the error.
      */
    def apply[F[_], E, A](
      replay: Resource[F, Replay[F, E]],
    )(
      completed: (SeqNr, Journaller[F, E], Snapshotter[F, S]) => Resource[F, A],
    )(implicit
      F: ApplicativeError[F, Throwable],
    ): Recovering[F, S, E, A] = {

      val replay1    = replay
      val completed1 = completed

      new Recovering[F, S, E, A] {
        override def replay = replay1

        override def completed(
          seqNr: SeqNr,
          journaller: Journaller[F, E],
          snapshotter: Snapshotter[F, S],
        ) = completed1(seqNr, journaller, snapshotter)

        override def transferred(
          seqNr: SeqNr,
          journaller: Journaller[F, E],
          snapshotter: Snapshotter[F, S],
        ) = completed1(seqNr, journaller, snapshotter)

        override def failed(
          cause: Throwable,
          journaller: DeleteEventsTo[F],
          snapshotter: Snapshotter[F, S],
        ) = Resource.raiseError[F, Unit, Throwable](cause)
      }
    }

    def const[F[_], E, A](
      replay: Resource[F, Replay[F, E]],
    )(
      completed: Resource[F, A],
    )(implicit
      F: ApplicativeError[F, Throwable],
    ): Recovering[F, S, E, A] = {

      val replay1    = replay
      val completed1 = completed

      new Recovering[F, S, E, A] {
        override def replay = replay1

        override def completed(
          seqNr: SeqNr,
          journaller: Journaller[F, E],
          snapshotter: Snapshotter[F, S],
        ) = completed1

        override def transferred(
          seqNr: SeqNr,
          journaller: Journaller[F, E],
          snapshotter: Snapshotter[F, S],
        ) = completed1

        override def failed(
          cause: Throwable,
          journaller: DeleteEventsTo[F],
          snapshotter: Snapshotter[F, S],
        ) = Resource.raiseError[F, Unit, Throwable](cause)
      }
    }

  }

  def apply[S]: Apply[S] = new Apply[S]

  final class Apply[S](private val b: Boolean = true) extends AnyVal {

    def apply[F[_], E, A](
      replay: Resource[F, Replay[F, E]],
    )(
      completed: (SeqNr, Journaller[F, E], Snapshotter[F, S]) => Resource[F, A],
    )(
      transferred: (SeqNr, Journaller[F, E], Snapshotter[F, S]) => Resource[F, A],
    )(
      failed: (Throwable, DeleteEventsTo[F], Snapshotter[F, S]) => Resource[F, Unit],
    ): Recovering[F, S, E, A] = {

      val replay1      = replay
      val completed1   = completed
      val transferred1 = transferred
      val failed1      = failed

      new Recovering[F, S, E, A] {

        override def replay = replay1

        override def completed(
          seqNr: SeqNr,
          journaller: Journaller[F, E],
          snapshotter: Snapshotter[F, S],
        ) = completed1(seqNr, journaller, snapshotter)

        override def transferred(
          seqNr: SeqNr,
          journaller: Journaller[F, E],
          snapshotter: Snapshotter[F, S],
        ) = transferred1(seqNr, journaller, snapshotter)

        override def failed(
          cause: Throwable,
          journaller: DeleteEventsTo[F],
          snapshotter: Snapshotter[F, S],
        ) = failed1(cause, journaller, snapshotter)
      }

    }

  }

  def const[S]: Const[S] = new Const[S]

  final class Const[S](private val b: Boolean = true) extends AnyVal {

    def apply[F[_], E, A](
      replay: Resource[F, Replay[F, E]],
    )(
      completed: Resource[F, A],
    )(
      transferred: Resource[F, A],
    )(
      failed: Resource[F, Unit],
    ): Recovering[F, S, E, A] = {

      val replay1      = replay
      val completed1   = completed
      val transferred1 = transferred
      val failed1      = failed

      new Recovering[F, S, E, A] {

        override def replay = replay1

        override def completed(
          seqNr: SeqNr,
          journaller: Journaller[F, E],
          snapshotter: Snapshotter[F, S],
        ) = completed1

        override def transferred(
          seqNr: SeqNr,
          journaller: Journaller[F, E],
          snapshotter: Snapshotter[F, S],
        ) = transferred1

        override def failed(
          cause: Throwable,
          journaller: DeleteEventsTo[F],
          snapshotter: Snapshotter[F, S],
        ) = failed1
      }

    }
  }

  implicit class RecoveringOps[F[_], S, E, A](val self: Recovering[F, S, E, A]) extends AnyVal {

    def convert[S1, E1, A1](sf: S => F[S1], ef: E => F[E1], e1f: E1 => F[E], af: A => Resource[F, A1])(implicit
      F: Monad[F],
    ): Recovering[F, S1, E1, A1] = new Recovering[F, S1, E1, A1] {

      override def replay = self.replay.map(_.convert(e1f))

      override def completed(
        seqNr: SeqNr,
        journaller: Journaller[F, E1],
        snapshotter: Snapshotter[F, S1],
      ) = {
        val journaller1  = journaller.convert(ef)
        val snapshotter1 = snapshotter.convert(sf)
        self.completed(seqNr, journaller1, snapshotter1).flatMap(af)
      }

      override def transferred(
        seqNr: SeqNr,
        journaller: Journaller[F, E1],
        snapshotter: Snapshotter[F, S1],
      ) = {
        val journaller1  = journaller.convert(ef)
        val snapshotter1 = snapshotter.convert(sf)
        self.transferred(seqNr, journaller1, snapshotter1).flatMap(af)
      }

      override def failed(
        cause: Throwable,
        journaller: DeleteEventsTo[F],
        snapshotter: Snapshotter[F, S1],
      ) = {
        val snapshotter1 = snapshotter.convert(sf)
        self.failed(cause, journaller, snapshotter1)
      }

    }

    def map[A1](f: A => A1): Recovering[F, S, E, A1] = new Recovering[F, S, E, A1] {

      override def replay = self.replay

      override def completed(
        seqNr: SeqNr,
        journaller: Journaller[F, E],
        snapshotter: Snapshotter[F, S],
      ) = self.completed(seqNr, journaller, snapshotter).map(f)

      override def transferred(
        seqNr: SeqNr,
        journaller: Journaller[F, E],
        snapshotter: Snapshotter[F, S],
      ) = self.transferred(seqNr, journaller, snapshotter).map(f)

      override def failed(
        cause: Throwable,
        journaller: DeleteEventsTo[F],
        snapshotter: Snapshotter[F, S],
      ) = self.failed(cause, journaller, snapshotter)

    }

    def mapM[A1](
      f: A => Resource[F, A1],
    ): Recovering[F, S, E, A1] = new Recovering[F, S, E, A1] {

      override def replay = self.replay

      override def completed(
        seqNr: SeqNr,
        journaller: Journaller[F, E],
        snapshotter: Snapshotter[F, S],
      ) = self.completed(seqNr, journaller, snapshotter).flatMap(f)

      override def transferred(
        seqNr: SeqNr,
        journaller: Journaller[F, E],
        snapshotter: Snapshotter[F, S],
      ) = self.transferred(seqNr, journaller, snapshotter).flatMap(f)

      override def failed(
        cause: Throwable,
        journaller: DeleteEventsTo[F],
        snapshotter: Snapshotter[F, S],
      ) = self.failed(cause, journaller, snapshotter)
    }
  }

  implicit class RecoveringReceiveEnvelopeOps[F[_], S, E, C](
    val self: Recovering[F, S, E, Receive[F, Envelope[C], Boolean]],
  ) extends AnyVal {

    def widen[S1 >: S, C1 >: C, E1 >: E](ef: E1 => F[E], cf: C1 => F[C])(implicit
      F: Monad[F],
    ): Recovering[F, S1, E1, Receive[F, Envelope[C1], Boolean]] =
      new Recovering[F, S1, E1, Receive[F, Envelope[C1], Boolean]] {

        override def replay = self.replay.map(_.convert(ef))

        override def completed(
          seqNr: SeqNr,
          journaller: Journaller[F, E1],
          snapshotter: Snapshotter[F, S1],
        ) = self.completed(seqNr, journaller, snapshotter).map(_.convert(cf, _.pure[F]))

        override def transferred(
          seqNr: SeqNr,
          journaller: Journaller[F, E1],
          snapshotter: Snapshotter[F, S1],
        ) = self.transferred(seqNr, journaller, snapshotter).map(_.convert(cf, _.pure[F]))

        override def failed(
          cause: Throwable,
          journaller: DeleteEventsTo[F],
          snapshotter: Snapshotter[F, S1],
        ) = self.failed(cause, journaller, snapshotter)
      }

    def typeless(ef: Any => F[E], cf: Any => F[C])(implicit
      F: Monad[F],
    ): Recovering[F, Any, Any, Receive[F, Envelope[Any], Boolean]] =
      widen(ef, cf)
  }
}
