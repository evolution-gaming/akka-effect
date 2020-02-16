package com.evolutiongaming.akkaeffect.persistence

import akka.persistence.Recovery
import cats.Monad
import cats.effect.Resource
import cats.implicits._

trait PersistenceSetup[F[_], S, C, E, R] {

  def persistenceId: String

  def recovery: Recovery = Recovery()

  def pluginIds: PluginIds = PluginIds.default

  // TODO onPreStart phase is missing

  // TODO describe resource release scope
  def recoveryStarted(
    snapshotOffer: Option[SnapshotOffer[S]],
    journaller: Journaller[F, E], // TODO move to onRecoveryCompleted
    snapshotter: Snapshotter[F, S] // TODO move to onRecoveryCompleted
  ): Resource[F, Recovering[F, S, C, E, R]]
}


object PersistenceSetup {

  implicit class PersistenceSetupOps[F[_], S, C, E, R](
    val self: PersistenceSetup[F, S, C, E, R]
  ) extends AnyVal {

    def convert[S1, C1, E1, R1](
      sf: S => F[S1],
      s1f: S1 => F[S],
      cf: C1 => F[C],
      ef: E => F[E1],
      e1f: E1 => F[E],
      rf: R => F[R1])(implicit
      F: Monad[F],
    ): PersistenceSetup[F, S1, C1, E1, R1] = {

      new PersistenceSetup[F, S1, C1, E1, R1] {

        def persistenceId = self.persistenceId

        def recoveryStarted(
          snapshotOffer: Option[SnapshotOffer[S1]],
          journaller: Journaller[F, E1],
          snapshotter: Snapshotter[F, S1]
        ) = {

          val snapshotOffer1 = snapshotOffer.traverse { offer =>
            s1f(offer.snapshot).map { snapshot => offer.copy(snapshot = snapshot) }
          }

          for {
            snapshotOffer <- Resource.liftF(snapshotOffer1)
            recovering    <- self.recoveryStarted(snapshotOffer, journaller.convert(ef), snapshotter.convert(sf))
          } yield {
            recovering.convert(sf, s1f, cf, e1f, rf)
          }
        }
      }
    }


    // TODO add more performant untyped
    def typeless(
      sf: Any => F[S],
      cf: Any => F[C],
      ef: Any => F[E])(implicit
      F: Monad[F],
    ): PersistenceSetup[F, Any, Any, Any, Any] = new PersistenceSetup[F, Any, Any, Any, Any] {

      def persistenceId = self.persistenceId

      def recoveryStarted(snapshotOffer: Option[SnapshotOffer[Any]], journaller: Journaller[F, Any], snapshotter: Snapshotter[F, Any]) = {

        val snapshotOffer1 = snapshotOffer.traverse { snapshotOffer =>
          sf(snapshotOffer.snapshot).map { snapshot => snapshotOffer.copy(snapshot = snapshot)}
        }

        for {
          snapshotOffer <- Resource.liftF(snapshotOffer1)
          recovering    <- self.recoveryStarted(snapshotOffer, journaller, snapshotter)
        } yield {
          recovering.typeless(sf, cf, ef)
        }
      }
    }
  }
}