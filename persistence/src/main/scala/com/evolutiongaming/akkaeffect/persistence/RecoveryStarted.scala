package com.evolutiongaming.akkaeffect.persistence

import cats.effect.Resource
import cats.syntax.all._
import cats.{Applicative, Monad}
import com.evolutiongaming.akkaeffect.{Envelope, Receive}
import com.evolutiongaming.catshelper.CatsHelper._

/**
  * Describes start of recovery phase
  *
  * @tparam S snapshot
  * @tparam E event
  * @tparam A recovery result
  */
trait RecoveryStarted[F[_], S, E, +A] {

  /**
    * Called upon starting recovery, resource will be released upon actor termination
    *
    * @see [[akka.persistence.SnapshotOffer]]
    */
  def apply(
    seqNr: SeqNr,
    snapshotOffer: Option[SnapshotOffer[S]]
  ): Resource[F, Recovering[F, S, E, A]]
}

object RecoveryStarted {

  def apply[S]: Apply[S] = new Apply[S]

  private[RecoveryStarted] final class Apply[S](private val b: Boolean = true) extends AnyVal {

    def apply[F[_], E, A](
      f: (SeqNr, Option[SnapshotOffer[S]]) => Resource[F, Recovering[F, S, E, A]]
    ): RecoveryStarted[F, S, E, A] = {
      (seqNr, snapshotOffer) => f(seqNr, snapshotOffer)
    }
  }


  def const[F[_], S, E, A](
    recovering: Resource[F, Recovering[F, S, E, A]]
  ): RecoveryStarted[F, S, E, A] = {
    (_, _) => recovering
  }


  implicit class RecoveryStartedOps[F[_], S, E, A](
    val self: RecoveryStarted[F, S, E, A]
  ) extends AnyVal {

    def convert[S1, E1, A1](
      sf: S => F[S1],
      s1f: S1 => F[S],
      ef: E => F[E1],
      e1f: E1 => F[E],
      af: A => Resource[F, A1])(implicit
      F: Monad[F],
    ): RecoveryStarted[F, S1, E1, A1] = {

      (seqNr, snapshotOffer) => {

        val snapshotOffer1 = snapshotOffer.traverse { snapshotOffer =>
          s1f(snapshotOffer.snapshot).map { snapshot => snapshotOffer.as(snapshot) }
        }

        for {
          snapshotOffer <- snapshotOffer1.toResource
          recovering    <- self(seqNr, snapshotOffer)
        } yield {
          recovering.convert(sf, ef, e1f, af)
        }
      }
    }


    def map[A1](f: A => A1)(implicit F: Applicative[F]): RecoveryStarted[F, S, E, A1] = {
      (seqNr, snapshotOffer) => {
        self(seqNr, snapshotOffer).map { _.map(f) }
      }
    }


    def mapM[A1](f: A => Resource[F, A1])(implicit F: Applicative[F]): RecoveryStarted[F, S, E, A1] = {
      (seqNr, snapshotOffer) => {
        self(seqNr, snapshotOffer).map { _.mapM(f) }
      }
    }
  }


  implicit class RecoveryReceiveEnvelopeOps[F[_], S, E, C](
    val self: RecoveryStarted[F, S, E, Receive[F, Envelope[C], Boolean]]
  ) extends AnyVal {

    def widen[S1 >: S, C1 >: C, E1 >: E](
      sf: S1 => F[S],
      ef: E1 => F[E],
      cf: C1 => F[C])(implicit
      F: Monad[F],
    ): RecoveryStarted[F, S1, E1, Receive[F, Envelope[C1], Boolean]] = {
      (seqNr, snapshotOffer) => {

        val snapshotOffer1 = snapshotOffer.traverse { snapshotOffer =>
          sf(snapshotOffer.snapshot).map { snapshot => snapshotOffer.copy(snapshot = snapshot) }
        }

        for {
          snapshotOffer <- snapshotOffer1.toResource
          recovering    <- self(seqNr, snapshotOffer)
        } yield {
          recovering.widen(ef, cf)
        }
      }
    }


    def typeless(
      sf: Any => F[S],
      ef: Any => F[E],
      cf: Any => F[C])(implicit
      F: Monad[F],
    ): RecoveryStarted[F, Any, Any, Receive[F, Envelope[Any], Boolean]] = {
      widen[Any, Any, Any](sf, ef, cf)
    }
  }
}
