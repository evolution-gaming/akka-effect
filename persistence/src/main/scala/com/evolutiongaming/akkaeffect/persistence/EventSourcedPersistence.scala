package com.evolutiongaming.akkaeffect.persistence

import akka.actor.ActorSystem
import akka.persistence.{EventStoreInterop, SnapshotStoreInterop}
import cats.effect.Async
import com.evolutiongaming.catshelper.{FromFuture, LogOf, ToTry}

import scala.concurrent.duration.*

trait EventSourcedPersistence[F[_], S, E] {

  def snapshotStore(eventSourced: EventSourced[_]): F[SnapshotStore[F, S]]

  def eventStore(eventSourced: EventSourced[_]): F[EventStore[F, E]]

}

object EventSourcedPersistence {

  def fromAkkaPlugins[F[_]: Async: FromFuture: ToTry: LogOf](
    system: ActorSystem,
    timeout: FiniteDuration,
    capacity: Int,
  ): EventSourcedPersistence[F, Any, Any] = new EventSourcedPersistence[F, Any, Any] {

    val persistence = akka.persistence.Persistence(system)

    override def snapshotStore(eventSourced: EventSourced[_]): F[SnapshotStore[F, Any]] = {
      val pluginId = eventSourced.pluginIds.snapshot.getOrElse("")
      SnapshotStoreInterop[F](persistence, timeout, pluginId, eventSourced.eventSourcedId)
    }

    override def eventStore(eventSourced: EventSourced[_]): F[EventStore[F, Any]] = {
      val pluginId = eventSourced.pluginIds.journal.getOrElse("")
      EventStoreInterop[F](persistence, timeout, capacity, pluginId, eventSourced.eventSourcedId)
    }
  }

}
