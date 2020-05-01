package com.evolutiongaming.akkaeffect.persistence

import cats.effect.Resource
import cats.Monad
import cats.implicits._
import com.evolutiongaming.akkaeffect.ReceiveAny
import com.evolutiongaming.catshelper.CatsHelper._

/**
  * Describes "Recovery" phase
  *
  * @tparam S snapshot
  * @tparam C command
  * @tparam E event
  * @tparam R reply
  */
trait RecoveringAny[F[_], S, C, E, R] {
  /**
    * Used to replay events during recovery against passed state,
    * resource will be released when recovery is completed
    */
  def replay: Resource[F, Replay1[F, E]]

  /**
    * Called when recovery completed, resource will be released upon actor termination
    *
    * @see [[akka.persistence.RecoveryCompleted]]
    * @return None to stop actor, Some to continue
    */
  def completed(
    seqNr: SeqNr,
    journaller: Journaller[F, E],
    snapshotter: Snapshotter[F, S]
  ): Resource[F, Option[ReceiveAny[F, C]]]
}

object RecoveringAny {
  
}
