package akka.persistence

import akka.actor.ActorRef

trait SnapshotterPublic extends Snapshotter {
  def snapshotStore: ActorRef
}
