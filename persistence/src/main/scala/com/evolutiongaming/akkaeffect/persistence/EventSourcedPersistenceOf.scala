package com.evolutiongaming.akkaeffect.persistence

import akka.actor.ActorSystem
import akka.persistence.{ExtendedSnapshoterInterop, ReplayerInterop, JournallerInterop, SnapshotSelectionCriteria}

import cats.syntax.all._
import cats.Applicative
import cats.effect.Async

import com.evolutiongaming.catshelper.ToTry
import com.evolutiongaming.catshelper.FromFuture
import com.evolutiongaming.akkaeffect.persistence.SeqNr
import com.evolutiongaming.sstream.Stream

import scala.concurrent.duration._

trait EventSourcedPersistenceOf[F[_], S, E] {

  def apply(
    eventSourced: EventSourced[_]
  ): F[EventSourcedPersistence[F, S, E]]

}

object EventSourcedPersistenceOf {

  def const[F[_]: Applicative, S, E](
    store: EventSourcedPersistence[F, S, E]
  ): EventSourcedPersistenceOf[F, S, E] =
    _ => store.pure[F]

  def fromAkka[F[_]: Async: ToTry: FromFuture, S, E](
    system: ActorSystem,
    timeout: FiniteDuration
  ): F[EventSourcedPersistenceOf[F, S, E]] =
    for {
      snapshotterOf <- ExtendedSnapshoterInterop[F](system, timeout)
      replayerOf    <- ReplayerInterop[F](system, timeout)
      journallerOf  <- JournallerInterop[F](system, timeout)
    } yield new EventSourcedPersistenceOf[F, S, E] {

      override def apply(eventSourced: EventSourced[_]): F[EventSourcedPersistence[F, S, E]] = {

        val defaultPluginId  = ""
        val snapshotPluginId = eventSourced.pluginIds.snapshot.getOrElse(defaultPluginId)
        val journalPluginId  = eventSourced.pluginIds.journal.getOrElse(defaultPluginId)

        for {
          extendedSn <- snapshotterOf[S](snapshotPluginId, eventSourced.eventSourcedId)
          replayer   <- replayerOf[E](journalPluginId, eventSourced.eventSourcedId)
        } yield new EventSourcedPersistence[F, S, E] {

          override def recover: F[EventSourcedPersistence.Recovery[F, S, E]] =
            extendedSn
              .load(SnapshotSelectionCriteria(), SeqNr.Max)
              .flatten
              .map { snapshotOffer =>
                new EventSourcedPersistence.Recovery[F, S, E] {

                  override def snapshot: Option[Snapshot[S]] = snapshotOffer

                  override def events: Stream[F, Event[E]] = {
                    val fromSeqNr = snapshotOffer.map(_.metadata.seqNr + 1).getOrElse(SeqNr.Min)
                    val events    = replayer.replay(fromSeqNr, SeqNr.Max, Long.MaxValue)
                    Stream.lift(events).flatten
                  }
                }
              }

          override def journaller(seqNr: SeqNr): F[Journaller[F, E]] = journallerOf[E](journalPluginId, eventSourced.eventSourcedId, seqNr)

          override def snapshotter: F[Snapshotter[F, S]] = extendedSn.snapshotter.pure[F]

        }
      }

    }

}
