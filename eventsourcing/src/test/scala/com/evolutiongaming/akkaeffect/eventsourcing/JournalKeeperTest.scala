package com.evolutiongaming.akkaeffect.eventsourcing

import java.time.Instant

import akka.persistence.SnapshotSelectionCriteria
import cats.Monad
import cats.effect.concurrent.{Deferred, Ref}
import cats.effect.{Clock, Concurrent, IO, Sync, Timer}
import cats.syntax.all._
import com.evolutiongaming.akkaeffect.IOSuite._
import com.evolutiongaming.akkaeffect.persistence.{SeqNr, SnapshotMetadata, Snapshotter}
import com.evolutiongaming.catshelper.Log
import com.evolutiongaming.catshelper.ClockHelper._
import org.scalatest.funsuite.AsyncFunSuite
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._

class JournalKeeperTest extends AsyncFunSuite with Matchers {
  import JournalKeeperTest._

  test("save snapshot on startup") {
    `save snapshot on startup`[IO].run()
  }

  test("save snapshot every n events") {
    `save snapshot every n events`[IO].run()
  }

  test("not save snapshots in parallel") {
    `not save snapshots in parallel`[IO].run()
  }

  test("save snapshot after batch of events save") {
    `save snapshot after batch of events saved`[IO].run()
  }

  test("save snapshot and delete previous") {
    `save snapshot and delete previous`[IO].run()
  }

  test("track snapshots saved externally") {
    `track snapshots saved externally`[IO].run()
  }

  test("not delete snapshot twice") {
    `not delete snapshot twice`[IO].run()
  }

  test("not delete snapshots twice") {
    `not delete snapshots twice`[IO].run()
  }

  test("delete old events") {
    `delete old events`[IO].run()
  }

  test("track events deleted externally") {
    `track events deleted externally`.run()
  }

  test("ignore not yet saved previous snapshot if next is available") {
    `ignore not yet saved previous snapshot if next is available`.run()
  }

  test("not all candidates are used for snapshots") {
    `not all candidates are used for snapshots`.run()
  }

  test("maintain cooldown between snapshots") {
    `maintain cooldown between snapshots`.run()
  }

  test("delay the very first snapshot") {
    `delay the very first snapshot`.run()
  }

  private def `save snapshot on startup`[F[_] : Concurrent : Timer]: F[Unit] = {

    type S = F[Unit]

    val config = JournalKeeper.Config(
      saveSnapshotPerEvents = 2,
      saveSnapshotCooldown = 0.millis)

    for {
      deferred      <- Deferred[F, Unit]
      actions       <- Actions.of[F, S]
      metadata       = SnapshotMetadata(seqNr = 2, timestamp = Instant.ofEpochMilli(0))
      journalKeeper <- journalKeeperOf(4, ().pure[F], metadata.some, config, actions)
      _             <- journalKeeper.eventsSaved(5, ().pure[F])
      _             <- journalKeeper.eventsSaved(6, deferred.complete(()))
      _             <- deferred.get
      actions       <- actions.get
      _              = actions.take(2) shouldEqual List(Action.SaveSnapshot(4), Action.DeleteSnapshot(2))
    } yield {}
  }


  private def `save snapshot every n events`[F[_] : Concurrent : Timer]: F[Unit] = {

    type S = F[Unit]

    val config = JournalKeeper.Config(
      saveSnapshotPerEvents = 2,
      saveSnapshotCooldown = 0.millis,
      deleteOldSnapshots = false)

    for {
      deferred      <- Deferred[F, Unit]
      actions       <- Actions.of[F, S]
      journalKeeper <- journalKeeperOf(0, ().pure[F], none, config, actions)
      _             <- journalKeeper.eventsSaved(1, ().pure[F])
      _             <- journalKeeper.eventsSaved(2, ().pure[F])
      _             <- journalKeeper.eventsSaved(3, ().pure[F])
      _             <- journalKeeper.eventsSaved(4, deferred.complete(()))
      _             <- deferred.get
      actions       <- actions.get
      _              = actions shouldEqual List(Action.SaveSnapshot(2), Action.SaveSnapshot(4))
    } yield {}
  }


  private def `not save snapshots in parallel`[F[_] : Concurrent : Timer]: F[Unit] = {

    type S = F[Unit]

    val config = JournalKeeper.Config(
      saveSnapshotPerEvents = 2,
      saveSnapshotCooldown = 0.millis,
      deleteOldSnapshots = false)

    for {
      deferred0     <- Deferred[F, Unit]
      deferred1     <- Deferred[F, Unit]
      actions       <- Actions.of[F, S]
      journalKeeper <- journalKeeperOf(0, ().pure[F], none, config, actions)
      _             <- journalKeeper.eventsSaved(1, ().pure[F])
      _             <- journalKeeper.eventsSaved(2, deferred0.get)
      _             <- journalKeeper.eventsSaved(3, ().pure[F])
      _             <- journalKeeper.eventsSaved(4, ().pure[F])
      _             <- journalKeeper.eventsSaved(5, ().pure[F])
      _             <- journalKeeper.eventsSaved(6, deferred1.complete(()))
      _             <- deferred0.complete(())
      _             <- deferred1.get
      actions       <- actions.get
      _              = actions shouldEqual List(Action.SaveSnapshot(2), Action.SaveSnapshot(6))
    } yield {}
  }


  private def `save snapshot after batch of events saved`[F[_] : Concurrent : Timer]: F[Unit] = {

    type S = F[Unit]

    val config = JournalKeeper.Config(
      saveSnapshotPerEvents = 2,
      saveSnapshotCooldown = 0.millis,
      deleteOldSnapshots = false)

    for {
      deferred      <- Deferred[F, Unit]
      actions       <- Actions.of[F, S]
      journalKeeper <- journalKeeperOf(0, ().pure[F], none, config, actions)
      _             <- journalKeeper.eventsSaved(100, deferred.complete(()))
      _             <- deferred.get
      actions       <- actions.get
      _              = actions shouldEqual List(Action.SaveSnapshot(100))
    } yield {}
  }


  private def `save snapshot and delete previous`[F[_] : Concurrent : Timer]: F[Unit] = {

    type S = F[Unit]

    val config = JournalKeeper.Config(
      saveSnapshotPerEvents = 2,
      saveSnapshotCooldown = 0.millis)

    for {
      deferred      <- Deferred[F, Unit]
      actions       <- Actions.of[F, S]
      metadata       = SnapshotMetadata(seqNr = 2, timestamp = Instant.ofEpochMilli(0))
      journalKeeper <- journalKeeperOf(4, ().pure[F], metadata.some, config, actions)
      _             <- journalKeeper.eventsSaved(6, deferred.complete(()))
      _             <- deferred.get
      actions       <- actions.get
      _              = actions.take(3) shouldEqual List(
        Action.SaveSnapshot(4),
        Action.DeleteSnapshot(2),
        Action.SaveSnapshot(6))
    } yield {}
  }


  private def `track snapshots saved externally`[F[_] : Concurrent : Timer]: F[Unit] = {
    type S = F[Unit]

    val config = JournalKeeper.Config(
      saveSnapshotPerEvents = 2,
      saveSnapshotCooldown = 0.millis,
      deleteOldSnapshots = false)

    for {
      deferred      <- Deferred[F, Unit]
      actions       <- Actions.of[F, S]
      metadata       = SnapshotMetadata(seqNr = 2, timestamp = Instant.ofEpochMilli(0))
      journalKeeper <- journalKeeperOf(2, ().pure[F], metadata.some, config, actions)
      _             <- journalKeeper.snapshotter.save(3, ().pure[F]).flatten
      _             <- journalKeeper.eventsSaved(4, ().pure[F])
      _             <- journalKeeper.eventsSaved(5, deferred.complete(()))
      _             <- deferred.get
      actions       <- actions.get
      _              = actions shouldEqual List(
        Action.SaveSnapshot(3),
        Action.SaveSnapshot(5))
    } yield {}
  }


  private def `not delete snapshot twice`[F[_] : Concurrent : Timer]: F[Unit] = {

    type S = F[Unit]

    val config = JournalKeeper.Config(
      saveSnapshotPerEvents = 2,
      saveSnapshotCooldown = 0.millis)

    for {
      deferred      <- Deferred[F, Unit]
      actions       <- Actions.of[F, S]
      metadata       = SnapshotMetadata(seqNr = 2, timestamp = Instant.ofEpochMilli(0))
      journalKeeper <- journalKeeperOf(3, ().pure[F], metadata.some, config, actions)
      _             <- journalKeeper.snapshotter.delete(2).flatten
      _             <- journalKeeper.eventsSaved(4, ().pure[F])
      _             <- journalKeeper.eventsSaved(6, deferred.complete(()))
      _             <- deferred.get
      actions       <- actions.get
      _              = actions.take(3) shouldEqual List(
        Action.DeleteSnapshot(2),
        Action.SaveSnapshot(4),
        Action.SaveSnapshot(6))
    } yield {}
  }


  private def `not delete snapshots twice`[F[_] : Concurrent : Timer]: F[Unit] = {

    type S = F[Unit]

    val config = JournalKeeper.Config(
      saveSnapshotPerEvents = 2,
      saveSnapshotCooldown = 0.millis)

    for {
      deferred      <- Deferred[F, Unit]
      actions       <- Actions.of[F, S]
      metadata       = SnapshotMetadata(seqNr = 2, timestamp = Instant.ofEpochMilli(0))
      journalKeeper <- journalKeeperOf(3, ().pure[F], metadata.some, config, actions)
      criteria       = SnapshotSelectionCriteria(maxSequenceNr = 3)
      _             <- journalKeeper.snapshotter.delete(criteria).flatten
      _             <- journalKeeper.eventsSaved(4, ().pure[F])
      _             <- journalKeeper.eventsSaved(6, deferred.complete(()))
      _             <- deferred.get
      actions       <- actions.get
      _              = actions.take(3) shouldEqual List(
        Action.DeleteSnapshots(criteria),
        Action.SaveSnapshot(4),
        Action.SaveSnapshot(6))
    } yield {}
  }


  private def `delete old events`[F[_] : Concurrent : Timer]: F[Unit] = {

    type S = F[Unit]

    val config = JournalKeeper.Config(
      saveSnapshotPerEvents = 2,
      saveSnapshotCooldown = 0.millis,
      deleteOldSnapshots = false,
      deleteOldEvents = true)

    for {
      deferred      <- Deferred[F, Unit]
      actions       <- Actions.of[F, S]
      metadata       = SnapshotMetadata(seqNr = 2, timestamp = Instant.ofEpochMilli(0))
      journalKeeper <- journalKeeperOf(3, ().pure[F], metadata.some, config, actions)
      _             <- journalKeeper.eventsSaved(4, ().pure[F])
      _             <- journalKeeper.eventsSaved(6, deferred.complete(()))
      _             <- deferred.get
      actions       <- actions.get
      _              = actions.take(3) shouldEqual List(
        Action.SaveSnapshot(4),
        Action.DeleteEventsTo(2),
        Action.SaveSnapshot(6))
    } yield {}
  }


  private def `track events deleted externally`[F[_] : Concurrent : Timer]: F[Unit] = {
    type S = F[Unit]

    val config = JournalKeeper.Config(
      saveSnapshotPerEvents = 2,
      saveSnapshotCooldown = 0.millis,
      deleteOldSnapshots = false,
      deleteOldEvents = true)

    for {
      deferred0     <- Deferred[F, Unit]
      deferred1     <- Deferred[F, Unit]
      actions       <- Actions.of[F, S]
      metadata       = SnapshotMetadata(seqNr = 2, timestamp = Instant.ofEpochMilli(0))
      journalKeeper <- journalKeeperOf(3, ().pure[F], metadata.some, config, actions)
      _             <- journalKeeper.journaller.deleteTo(3).flatten
      _             <- journalKeeper.eventsSaved(4, ().pure[F])
      _             <- journalKeeper.eventsSaved(7, deferred0.complete(()))
      _             <- deferred0.get
      _             <- journalKeeper.eventsSaved(10, deferred1.complete(()))
      _             <- deferred1.get
      actions       <- actions.get
      _              = actions.take(5) shouldEqual List(
        Action.DeleteEventsTo(3),
        Action.SaveSnapshot(4),
        Action.SaveSnapshot(7),
        Action.DeleteEventsTo(4),
        Action.SaveSnapshot(10))
    } yield {}
  }


  private def `ignore not yet saved previous snapshot if next is available`[F[_] : Concurrent : Timer]: F[Unit] = {
    type S = F[Unit]

    val config = JournalKeeper.Config(
      saveSnapshotPerEvents = 2,
      saveSnapshotCooldown = 0.millis,
      deleteOldSnapshots = false)

    for {
      deferred0     <- Deferred[F, Unit]
      deferred1     <- Deferred[F, Unit]
      actions       <- Actions.of[F, S]
      journalKeeper <- journalKeeperOf(0, ().pure[F], none, config, actions)
      _             <- journalKeeper.eventsSaved(2, deferred0.get)
      _             <- journalKeeper.eventsSaved(4, ().pure[F])
      _             <- journalKeeper.eventsSaved(6, deferred1.complete(()))
      _             <- deferred0.complete(())
      _             <- deferred1.get
      actions       <- actions.get
      _              = actions shouldEqual List(
        Action.SaveSnapshot(2),
        Action.SaveSnapshot(6))
    } yield {}
  }


  private def `not all candidates are used for snapshots`[F[_] : Concurrent : Timer]: F[Unit] = {
    type S = F[Unit]

    val config = JournalKeeper.Config(
      saveSnapshotPerEvents = 2,
      saveSnapshotCooldown = 0.millis,
      deleteOldSnapshots = false)

    def journalKeeperOf(
      seqNr: SeqNr,
      state: F[Unit],
      actions: Actions[F],
    ): F[JournalKeeper[F, S, S]] = {

      val snapshotOf = JournalKeeper.SnapshotOf[S] { a =>
        val snapshot = if (a.seqNr % 2 == 0) none else a.value.some
        snapshot.pure[F]
      }

      JournalKeeper.of[F, S, S](
        JournalKeeper.Candidate(seqNr, state),
        none,
        journallerOf(actions),
        snapshotterOf[F, Unit](actions),
        snapshotOf,
        config,
        Log.empty)
    }

    for {
      deferred      <- Deferred[F, Unit]
      actions       <- Actions.of[F, S]
      journalKeeper <- journalKeeperOf(0, ().pure[F], actions)
      _             <- journalKeeper.eventsSaved(4, ().pure[F])
      _             <- journalKeeper.eventsSaved(5, deferred.complete(()))
      _             <- deferred.get
      actions       <- actions.get
      _              = actions shouldEqual List(Action.SaveSnapshot(5))
    } yield {}
  }


  private def `maintain cooldown between snapshots`[F[_] : Concurrent : Timer]: F[Unit] = {
    type S = F[Unit]

    val config = JournalKeeper.Config(
      saveSnapshotPerEvents = 2,
      saveSnapshotCooldown = 100.millis,
      deleteOldSnapshots = false)

    for {
      deferred      <- Deferred[F, Unit]
      actions       <- Actions.of[F, S]
      timestamp     <- Clock[F].instant
      metadata       = SnapshotMetadata(seqNr = 2, timestamp = timestamp)
      journalKeeper <- journalKeeperOf(2, ().pure[F], metadata.some, config, actions)
      _             <- journalKeeper.eventsSaved(4, ().pure[F])
      _             <- Timer[F].sleep(config.saveSnapshotCooldown)
      _             <- journalKeeper.eventsSaved(6, ().pure[F])
      _             <- journalKeeper.eventsSaved(8, ().pure[F])
      _             <- Timer[F].sleep(config.saveSnapshotCooldown)
      _             <- journalKeeper.eventsSaved(10, deferred.complete(()))
      _             <- deferred.get
      actions       <- actions.get
      _              = actions shouldEqual List(
        Action.SaveSnapshot(6),
        Action.SaveSnapshot(10))
    } yield {}
  }


  private def `delay the very first snapshot`[F[_] : Concurrent : Timer]: F[Unit] = {
    type S = F[Unit]

    val config = JournalKeeper.Config(
      saveSnapshotPerEvents = 2,
      saveSnapshotCooldown = 100.millis,
      deleteOldSnapshots = false)

    for {
      deferred      <- Deferred[F, Unit]
      actions       <- Actions.of[F, S]
      journalKeeper <- journalKeeperOf(0, ().pure[F], none, config, actions)
      _             <- journalKeeper.eventsSaved(2, ().pure[F])
      _             <- Timer[F].sleep(config.saveSnapshotCooldown)
      _             <- journalKeeper.eventsSaved(4, deferred.complete(()))
      _             <- deferred.get
      actions       <- actions.get
      _              = actions shouldEqual List(Action.SaveSnapshot(4))
    } yield {}
  }
}

object JournalKeeperTest {

  trait Actions[F[_]] {

    def add(a: Action): F[Unit]

    def get: F[List[Action]]
  }

  object Actions {

    def of[F[_] : Sync, A]: F[Actions[F]] = {
      Ref[F]
        .of(List.empty[Action])
        .map { ref =>
          new Actions[F] {

            def add(a: Action) = ref.update { a :: _ }

            def get = ref.get.map { _.reverse }
          }
        }
    }
  }

  sealed trait Action

  object Action {
    final case class SaveSnapshot(seqNr: SeqNr) extends Action
    final case class DeleteSnapshot(seqNr: SeqNr) extends Action
    final case class DeleteSnapshots(criteria: SnapshotSelectionCriteria) extends Action
    final case class DeleteEventsTo(seqNr: SeqNr) extends Action
  }


  def snapshotterOf[F[_] : Monad : Clock, A](
    actions: Actions[F]
  ): Snapshotter[F, F[A]] = new Snapshotter[F, F[A]] {

    def save(seqNr: SeqNr, snapshot: F[A]) = {
      for {
        timestamp <- Clock[F].instant
        _         <- actions.add(Action.SaveSnapshot(seqNr))
        _         <- snapshot
      } yield {
        timestamp.pure[F]
      }
    }

    def delete(seqNr: SeqNr) = {
      actions
        .add(Action.DeleteSnapshot(seqNr))
        .map { _.pure[F] }
    }

    def delete(criteria: SnapshotSelectionCriteria) = {
      actions
        .add(Action.DeleteSnapshots(criteria))
        .map { _.pure[F] }
    }
  }


  def journallerOf[F[_] : Monad](
    actions: Actions[F]
  ): Journaller[F] = {
    (seqNr: SeqNr) => {
      actions
        .add(Action.DeleteEventsTo(seqNr))
        .map { _.pure[F] }
    }
  }

  def journalKeeperOf[F[_] : Concurrent : Timer](
    seqNr: SeqNr,
    state: F[Unit],
    snapshotOffer: Option[SnapshotMetadata],
    config: JournalKeeper.Config,
    actions: Actions[F],
  ): F[JournalKeeper[F, F[Unit], F[Unit]]] = {

    JournalKeeper.of[F, F[Unit], F[Unit]](
      JournalKeeper.Candidate(seqNr, state),
      snapshotOffer,
      journallerOf(actions),
      snapshotterOf[F, Unit](actions),
      _.value.some.pure[F],
      config,
      Log.empty)
  }
}
