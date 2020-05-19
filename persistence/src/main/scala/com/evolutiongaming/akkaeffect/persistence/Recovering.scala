package com.evolutiongaming.akkaeffect.persistence

import cats.Monad
import cats.effect.Resource
import cats.implicits._
import com.evolutiongaming.akkaeffect.Receive

/**
  * Describes "Recovery" phase
  *
  * @tparam S snapshot
  * @tparam E event
  * @tparam C command
  */
trait Recovering[F[_], S, E, C] {
  /**
    * Used to replay events during recovery against passed state,
    * resource will be released when recovery is completed
    */
  def replay: Resource[F, Replay[F, E]]

  /**
    * Called when recovery completed, resource will be released upon actor termination
    *
    * @see [[akka.persistence.RecoveryCompleted]]
    */
  def completed(
    seqNr: SeqNr,
    journaller: Journaller[F, E],
    snapshotter: Snapshotter[F, S]
  ): Resource[F, Receive[F, C]]
}

object Recovering {

  def empty[F[_]: Monad, S, E, C]: Recovering[F, S, E, C] = new Recovering[F, S, E, C] {

    def replay = Replay.empty[F, E].pure[Resource[F, *]]

    def completed(seqNr: SeqNr, journaller: Journaller[F, E], snapshotter: Snapshotter[F, S]) = {
      Receive.empty[F, C].pure[Resource[F, *]]
    }
  }


  implicit class RecoveringOps[F[_], S, E, C](val self: Recovering[F, S, E, C]) extends AnyVal {

    def convert[S1, E1, C1](
      sf: S => F[S1],
      ef: E => F[E1],
      e1f: E1 => F[E],
      cf: C1 => F[C])(implicit
      F: Monad[F],
    ): Recovering[F, S1, E1, C1] = new Recovering[F, S1, E1, C1] {

      def replay = self.replay.map { _.convert(e1f) }

      def completed(
        seqNr: SeqNr,
        journaller: Journaller[F, E1],
        snapshotter: Snapshotter[F, S1]
      ) = {
        val journaller1 = journaller.convert(ef)
        val snapshotter1 = snapshotter.convert(sf)
        self
          .completed(seqNr, journaller1, snapshotter1)
          .map { _.convert(cf) }
      }
    }


    def widen[S1 >: S, C1 >: C, E1 >: E](
      ef: E1 => F[E],
      cf: C1 => F[C])(implicit
      F: Monad[F]
    ): Recovering[F, S1, E1, C1] = new Recovering[F, S1, E1, C1] {

      def replay = self.replay.map { _.convert(ef) }

      def completed(
        seqNr: SeqNr,
        journaller: Journaller[F, E1],
        snapshotter: Snapshotter[F, S1]
      ) = {
        self
          .completed(seqNr, journaller, snapshotter)
          .map { _.convert(cf) }
      }
    }


    def typeless(
      ef: Any => F[E],
      cf: Any => F[C])(implicit
      F: Monad[F]
    ): Recovering[F, Any, Any, Any] = widen(ef, cf)
  }
}
