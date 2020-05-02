package com.evolutiongaming.akkaeffect.persistence

import cats.effect.Resource
import com.evolutiongaming.akkaeffect.ReceiveAny

/**
  * Describes "Recovery" phase
  *
  * @tparam S snapshot
  * @tparam C command
  * @tparam E event
  */
trait RecoveringAny[F[_], S, C, E] {
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
  ): Resource[F, ReceiveAny[F, C]]
}

object RecoveringAny {

}
