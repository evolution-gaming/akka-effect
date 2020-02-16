package com.evolutiongaming.akkaeffect.persistence

import akka.persistence.Recovery
import cats.Monad
import cats.effect.Resource
import cats.implicits._

trait EventSourced[F[_], S, C, E, R] {

  def id: String

  def recovery: Recovery = Recovery()

  def pluginIds: PluginIds = PluginIds.default

  // TODO onPreStart phase is missing

  // TODO describe resource release scope
  def recoveryStarted(snapshotOffer: Option[SnapshotOffer[S]]): Resource[F, Recovering[F, S, C, E, R]]
}


object EventSourced {

  implicit class EventSourcedOps[F[_], S, C, E, R](
    val self: EventSourced[F, S, C, E, R]
  ) extends AnyVal {

    def convert[S1, C1, E1, R1](
      sf: S => F[S1],
      s1f: S1 => F[S],
      cf: C1 => F[C],
      ef: E => F[E1],
      e1f: E1 => F[E],
      rf: R => F[R1])(implicit
      F: Monad[F],
    ): EventSourced[F, S1, C1, E1, R1] = {

      new EventSourced[F, S1, C1, E1, R1] {

        def id = self.id

        def recoveryStarted(snapshotOffer: Option[SnapshotOffer[S1]]) = {

          val snapshotOffer1 = snapshotOffer.traverse { offer =>
            s1f(offer.snapshot).map { snapshot => offer.copy(snapshot = snapshot) }
          }

          for {
            snapshotOffer <- Resource.liftF(snapshotOffer1)
            recovering    <- self.recoveryStarted(snapshotOffer)
          } yield {
            recovering.convert(sf, s1f, cf, ef, e1f, rf)
          }
        }
      }
    }


    def widen[S1 >: S, C1 >: C, E1 >: E, R1 >: R](
      sf: S1 => F[S],
      cf: C1 => F[C],
      ef: E1 => F[E])(implicit
      F: Monad[F],
    ): EventSourced[F, S1, C1, E1, R1] = new EventSourced[F, S1, C1, E1, R1] {

      def id = self.id

      def recoveryStarted(snapshotOffer: Option[SnapshotOffer[S1]]) = {

        val snapshotOffer1 = snapshotOffer.traverse { snapshotOffer =>
          sf(snapshotOffer.snapshot).map { snapshot => snapshotOffer.copy(snapshot = snapshot)}
        }

        for {
          snapshotOffer <- Resource.liftF(snapshotOffer1)
          recovering    <- self.recoveryStarted(snapshotOffer)
        } yield {
          recovering.widen(sf, cf, ef)
        }
      }
    }


    def typeless(
      sf: Any => F[S],
      cf: Any => F[C],
      ef: Any => F[E])(implicit
      F: Monad[F],
    ): EventSourced[F, Any, Any, Any, Any] = widen[Any, Any, Any, Any](sf, cf, ef)
  }
}