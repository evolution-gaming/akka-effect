package com.evolutiongaming.akkaeffect.persistence

import cats.effect.Resource
import cats.Monad
import cats.implicits._
import com.evolutiongaming.akkaeffect.Receive

/**
  * Describes "Recovery" phase
  *
  * @tparam S snapshot
  * @tparam C command
  * @tparam E event
  * @tparam R reply
  */
trait Recovering[F[_], S, C, E, R] {

  def initial: F[S]

  /**
    * Used to replay events during recovery against passed state, resource will be released when recovery is completed
    */
  def replay: Resource[F, Replay[F, S, E]]

  /**
    * Called when recovery completed, resource will be released upon actor termination
    *
    * @see [[akka.persistence.RecoveryCompleted]]
    * @return None to stop actor, Some to continue
    */
  def completed(
    state: S,
    seqNr: SeqNr,
    journaller: Journaller[F, E],
    snapshotter: Snapshotter[F, S]
  ): Resource[F, Option[Receive[F, C, R]]]
}

object Recovering {

  implicit class RecoveringOps[F[_], S, C, E, R](val self: Recovering[F, S, C, E, R]) extends AnyVal {

    def convert[S1, C1, E1, R1](
      sf: S => F[S1],
      s1f: S1 => F[S],
      cf: C1 => F[C],
      ef: E => F[E1],
      e1f: E1 => F[E],
      rf: R => F[R1])(implicit
      F: Monad[F],
    ): Recovering[F, S1, C1, E1, R1] = new Recovering[F, S1, C1, E1, R1] {

      val initial = self.initial.flatMap(sf)

      val replay = self.replay.map { _.convert(sf, s1f, e1f) }

      def completed(
        state: S1,
        seqNr: SeqNr,
        journaller: Journaller[F, E1],
        snapshotter: Snapshotter[F, S1]
      ) = {

        val journaller1 = journaller.convert(ef)
        val snapshotter1 = snapshotter.convert(sf)

        for {
          state   <- Resource.liftF(s1f(state))
          receive <- self.completed(state, seqNr, journaller1, snapshotter1)
        } yield for {
          receive <- receive
        } yield {
          receive.convert(cf, rf)
        }
      }
    }


    def widen[S1 >: S, C1 >: C, E1 >: E, R1 >: R](
      sf: S1 => F[S],
      cf: C1 => F[C],
      ef: E1 => F[E])(implicit
      F: Monad[F]
    ): Recovering[F, S1, C1, E1, R1] = new Recovering[F, S1, C1, E1, R1] {

      val initial = self.initial.asInstanceOf[F[S1]]

      val replay = self.replay.map { _.widen(sf, ef) }

      def completed(
        state: S1,
        seqNr: SeqNr,
        journaller: Journaller[F, E1],
        snapshotter: Snapshotter[F, S1]
      ) = {
        for {
          state   <- Resource.liftF(sf(state))
          receive <- self.completed(state, seqNr, journaller, snapshotter)
        } yield for {
          receive <- receive
        } yield {
          receive.widen(cf)
        }
      }
    }


    def typeless(
      sf: Any => F[S],
      cf: Any => F[C],
      ef: Any => F[E])(implicit
      F: Monad[F]
    ): Recovering[F, Any, Any, Any, Any] = widen(sf, cf, ef)
  }
}
