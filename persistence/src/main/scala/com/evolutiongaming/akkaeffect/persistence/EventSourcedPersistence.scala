package com.evolutiongaming.akkaeffect.persistence

import akka.actor.ActorSystem
import akka.persistence.EventStoreInterop
import akka.persistence.SnapshotStoreInterop
import cats.effect.Async
import com.evolutiongaming.catshelper.FromFuture
import com.evolutiongaming.catshelper.LogOf
import com.evolutiongaming.catshelper.ToTry

import scala.concurrent.duration._

trait EventSourcedPersistence[F[_]] {

  def snapshotStore[A](eventSourced: EventSourced[_]): F[SnapshotStore[F, A]]

  def eventStore[A](eventSourced: EventSourced[_]): F[EventStore[F, A]]

}

object EventSourcedPersistence {

  def fromAkkaPlugins[F[_]: Async: FromFuture: ToTry: LogOf](
    system: ActorSystem,
    timeout: FiniteDuration,
    capacity: Int
  ): EventSourcedPersistence[F] = new EventSourcedPersistence[F] {

    val persistence = akka.persistence.Persistence(system)

    override def snapshotStore[A](eventSourced: EventSourced[_]): F[SnapshotStore[F, A]] = {
      val pluginId = eventSourced.pluginIds.snapshot.getOrElse("")
      SnapshotStoreInterop[F, A](persistence, timeout, pluginId, eventSourced.eventSourcedId)
    }

    override def eventStore[A](eventSourced: EventSourced[_]): F[EventStore[F, A]] = {
      val pluginId = eventSourced.pluginIds.journal.getOrElse("")
      EventStoreInterop[F, A](persistence, timeout, capacity, pluginId, eventSourced.eventSourcedId)
    }
  }

}
