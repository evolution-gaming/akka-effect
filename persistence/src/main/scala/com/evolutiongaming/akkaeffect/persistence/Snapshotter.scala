package com.evolutiongaming.akkaeffect.persistence

import akka.persistence.SnapshotSelectionCriteria

trait Snapshotter[F[_], -A] {

  def save(a: A): F[F[Unit]]

  def delete(seqNr: SeqNr): F[F[Unit]]

  def delete(criteria: SnapshotSelectionCriteria): F[F[Unit]]
}
