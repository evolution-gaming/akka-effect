package com.evolutiongaming.akkaeffect.persistence

import akka.persistence.SnapshotSelectionCriteria

trait ExtendedSnapshotter[F[_], S] extends Snapshotter[F, S] { self =>

  def load(criteria: SnapshotSelectionCriteria, toSequenceNr: SeqNr): F[F[Option[Snapshot[S]]]]

  val snapshotter: Snapshotter[F, S] = self

}

object ExtendedSnapshotter {

  trait Of[F[_]] {

    def apply[S](snapshotPluginId: String, eventSourcedId: EventSourcedId): F[ExtendedSnapshotter[F, S]]

  }

}
