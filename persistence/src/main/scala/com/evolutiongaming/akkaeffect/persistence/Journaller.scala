package com.evolutiongaming.akkaeffect.persistence

import cats.data.{NonEmptyList => Nel}

trait Journaller[F[_], -A] {
  /*/**
    * @see [[akka.persistence.Eventsourced.deleteMessages]]
    */
  def delete(toSeqNr: SeqNr): Unit*/

  def append(events: Nel[A]): F[SeqNr]
}