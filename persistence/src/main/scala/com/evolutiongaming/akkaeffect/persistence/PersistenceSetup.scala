package com.evolutiongaming.akkaeffect.persistence

import akka.persistence.Recovery
import cats.Monad
import cats.effect.Resource
import cats.implicits._
import com.evolutiongaming.akkaeffect.Conversion
import com.evolutiongaming.akkaeffect.Conversion.implicits._

trait PersistenceSetup[F[_], S, C, E] {

  def persistenceId: String

  def recovery: Recovery = Recovery()

  def pluginIds: PluginIds = PluginIds.Default

  // TODO onPreStart phase is missing

  def recoveryStarted(
    offer: Option[SnapshotOffer[S]],
    journaller: Journaller[F, E], // TODO move to onRecoveryCompleted
    snapshotter: Snapshotter[F, S] // TODO move to onRecoveryCompleted
  ): Resource[F, Recovering[F, S, C, E]]
}


object PersistenceSetup {

  implicit class PersistenceSetupOps[F[_], S, C, E](val self: PersistenceSetup[F, S, C, E]) extends AnyVal {

    def untyped(implicit
      F: Monad[F],
      anyToS: Conversion[F, Any, S],
      anyToC: Conversion[F, Any, C],
      anyToE: Conversion[F, Any, E]
    ): PersistenceSetup[F, Any, Any, Any] = {

      new PersistenceSetup[F, Any, Any, Any] {

        def persistenceId = self.persistenceId

        def recoveryStarted(
          offer: Option[SnapshotOffer[Any]],
          journaller: Journaller[F, Any],
          snapshotter: Snapshotter[F, Any]
        ) = {

          val offer1 = offer
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