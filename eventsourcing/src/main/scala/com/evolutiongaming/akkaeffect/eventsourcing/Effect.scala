package com.evolutiongaming.akkaeffect.eventsourcing

import cats.Applicative
import cats.implicits._
import com.evolutiongaming.akkaeffect.persistence.SeqNr

/**
  * This function will be executed after events are stored
  */
trait Effect[F[_]] {

  /**
    * @param seqNr - either seqNr or error if failed to store events
    */
  def apply(seqNr: Either[Throwable, SeqNr]): F[Unit]
}

object Effect {

  def empty[F[_] : Applicative]: Effect[F] = _ => ().pure[F]
}
