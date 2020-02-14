package com.evolutiongaming.akkaeffect.persistence

import akka.persistence.Recovery
import cats.Monad
import cats.effect.Resource
import cats.implicits._
import com.evolutiongaming.akkaeffect.Convert
import com.evolutiongaming.akkaeffect.Convert.implicits._

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

    // TODO rename to convert
    def untyped(implicit
      F: Monad[F],
      anyToS: Convert[F, Any, S],
      anyToC: Convert[F, Any, C],
      anyToE: Convert[F, Any, E]
    ): PersistenceSetup[F, Any, Any, Any, Any] = {

      new PersistenceSetup[F, Any, Any, Any, Any] {

        def persistenceId = self.persistenceId

        def recoveryStarted(
          snapshotOffer: Option[SnapshotOffer[Any]],
          journaller: Journaller[F, Any],
          snapshotter: Snapshotter[F, Any]
        ) = {

          val offer1 = snapshotOffer
            .traverse { offer =>
              offer
                .snapshot
                .convert[F, S]
                .map { snapshot => offer.copy(snapshot = snapshot) }
            }

          for {
            offer      <- Resource.liftF(offer1)
            recovering <- self.recoveryStarted(offer, journaller, snapshotter)
          } yield {
            recovering.untyped
          }
        }
      }
    }
  }
}