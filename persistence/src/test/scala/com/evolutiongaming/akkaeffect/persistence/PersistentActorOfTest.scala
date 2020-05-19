package com.evolutiongaming.akkaeffect.persistence

import java.time.Instant

import akka.actor.{ActorIdentity, ActorRef, ActorSystem, Identify, ReceiveTimeout}
import akka.persistence.Recovery
import akka.testkit.TestActors
import cats.data.{NonEmptyList => Nel}
import cats.effect.concurrent.{Deferred, Ref}
import cats.effect.{Concurrent, IO, Resource, Sync, Timer}
import cats.implicits._
import com.evolutiongaming.akkaeffect.IOSuite._
import com.evolutiongaming.akkaeffect.persistence.InstrumentEventSourced.Action
import com.evolutiongaming.akkaeffect.testkit.Probe
import com.evolutiongaming.akkaeffect.{ActorSuite, _}
import com.evolutiongaming.catshelper.CatsHelper._
import com.evolutiongaming.catshelper.{FromFuture, ToFuture, ToTry}
import org.scalatest.funsuite.AsyncFunSuite
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._
import scala.reflect.ClassTag

class PersistentActorOfTest extends AsyncFunSuite with ActorSuite with Matchers {

  private implicit val toTry = ToTryFromToFuture.syncOrError[IO]

  test("all") {
    `persistentActorOf`[IO](actorSystem).run()
  }

  test("recover with empty state") {
    `recover with empty state`(actorSystem).run()
  }

  test("recover from snapshot") {
    `recover from snapshot`(actorSystem).run()
  }

  test("recover from snapshot with deleted events") {
    `recover from snapshot with deleted events`(actorSystem).run()
  }

  test("recover from events") {
    `recover from events`(actorSystem).run()
  }

  test("recover from events with deleted snapshot") {
    `recover from events with deleted snapshot`(actorSystem).run()
  }

  test("recover from snapshot and events") {
    `recover from snapshot and events`(actorSystem).run()
  }

  test("start stops") {
    `start stops`(actorSystem).run()
  }

  test("recoveryStarted stops") {
    `recoveryStarted stops`(actorSystem).run()
  }

  test("recoveryCompleted stops") {
    `recoveryCompleted stops`(actorSystem).run()
  }

  test("append many") {
    `append many`(actorSystem).run()
  }

  test("setReceiveTimeout") {
    setReceiveTimeout[IO](actorSystem).run()
  }

  private def `persistentActorOf`[F[_]: Concurrent: Timer: ToFuture: FromFuture: ToTry](
    actorSystem: ActorSystem
  ): F[Unit] = {

    type State = Int
    type Event = String

    sealed trait Cmd

    object Cmd {

      def timeout: Cmd = Timeout

      final case object Inc extends Cmd
      final case object Stop extends Cmd
      final case object Timeout extends Cmd
      final case class WithCtx[A](f: ActorCtx[F] => F[A]) extends Cmd
    }

    def eventSourcedOf(receiveTimeout: F[Unit]): EventSourcedOf[F, State, Event, Any] = {
      actorCtx => {

        val eventSourced = new EventSourced[F, State, Event, Any] {

          def eventSourcedId = EventSourcedId("id")

          def pluginIds = PluginIds.Empty

          def recovery = Recovery()

          def start = {
            val started = RecoveryStarted[F, State, Event, Any] { (_, _) =>

                val recovering: Recovering[F, State, Event, Any] = new Recovering[F, State, Event, Any] {

                  val replay = Replay.empty[F, Event].pure[Resource[F, *]]

                  def completed(
                    seqNr: SeqNr,
                    journaller: Journaller[F, Event],
                    snapshotter: Snapshotter[F, State]
                  ) = {

                    for {
                      stateRef <- Ref[F].of(0).toResource
                    } yield {
                      val receive = Receive[F, Cmd] { (msg, sender) =>

                        val reply = Reply.fromActorRef[F](to = sender, from = actorCtx.self)

                        msg match {
                          case a: Cmd.WithCtx[_] =>
                            for {
                              a <- a.f(actorCtx)
                              _ <- reply(a)
                            } yield false

                          case Cmd.Timeout =>
                            for {
                              _ <- actorCtx.setReceiveTimeout(Duration.Inf)
                              _ <- receiveTimeout
                            } yield false

                          case Cmd.Inc =>
                            for {
                              seqNr  <- journaller.append(Events.of("a")).flatten
                              _      <- stateRef.update { _ + 1 }
                              state  <- stateRef.get
                              result <- snapshotter.save(seqNr, state)
                              seqNr  <- journaller.append(Events.batched(Nel.of("b"), Nel.of("c", "d")))
                              seqNr  <- seqNr
                              _      <- result
                              _      <- stateRef.update { _ + 1 }
                              _      <- reply(seqNr)
                            } yield false

                          case Cmd.Stop =>
                            for {
                              _ <- reply("stopping")
                            } yield true
                        }
                      }

                      receive.typeless {
                        case ReceiveTimeout => Cmd.timeout.pure[F]
                        case a              => a.castM[F, Cmd]
                      }
                    }
                  }
                }
                recovering.pure[Resource[F, *]]
            }

            started.pure[Resource[F, *]]
          }
        }
        eventSourced.pure[F]
      }
    }

    def persistentActorOf(
      actorRef: ActorEffect[F, Any, Any],
      probe: Probe[F],
      receiveTimeout: F[Unit],
    ): F[Unit] = {

      val timeout = 10.seconds

      def withCtx[A: ClassTag](f: ActorCtx[F] => F[A]): F[A] = {
        for {
          a <- actorRef.ask(Cmd.WithCtx(f), timeout)
          a <- a
          a <- a.castM[F, A]
        } yield a
      }

      for {
        terminated0 <- probe.watch(actorRef.toUnsafe)
        dispatcher  <- withCtx { _.executor.pure[F] }
        _           <- Sync[F].delay { dispatcher.toString shouldEqual "Dispatcher[akka.actor.default-dispatcher]" }
        a           <- withCtx { ctx =>
          ActorRefOf
            .fromActorRefFactory[F](ctx.actorRefFactory)
            .apply(TestActors.blackholeProps, "child".some)
            .allocated
        }
        (child0, childRelease) = a
        terminated1 <- probe.watch(child0)
        children    <- withCtx { _.children }
        _           <- Sync[F].delay { children should contain(child0) }
        child        = withCtx { _.child("child") }
        child1      <- child
        _           <- Sync[F].delay { child1 shouldEqual child0.some }
        _           <- childRelease
        _           <- terminated1
        child1      <- child
        _           <- Sync[F].delay { child1 shouldEqual none[ActorRef] }
        children    <- withCtx { _.children }
        _           <- Sync[F].delay { children should not contain child0 }
        identity    <- actorRef.ask(Identify("id"), timeout).flatten
        identity    <- identity.castM[F, ActorIdentity]
        _           <- withCtx { _.setReceiveTimeout(1.millis) }
        _           <- receiveTimeout
        _           <- Sync[F].delay { identity shouldEqual ActorIdentity("id", actorRef.toUnsafe.some) }
        seqNr       <- actorRef.ask(Cmd.Inc, timeout).flatten
        _            = seqNr shouldEqual 4
        a           <- actorRef.ask(Cmd.Stop, timeout).flatten
        _            = a shouldEqual "stopping"
        _           <- terminated0
      } yield {}
    }

    for {
      receiveTimeout <- Deferred[F, Unit]
      eventSourcedOf <- eventSourcedOf(receiveTimeout.complete(()))
        .typeless(_.castM[F, State], _.castM[F, Event], _.pure[F])
        .convert[Any, Any, Any](_.pure[F], _.pure[F], _.pure[F], _.pure[F], _.pure[F])
        .pure[F]
      actorRefOf      = ActorRefOf.fromActorRefFactory[F](actorSystem)
      probe           = Probe.of[F](actorRefOf)
      actorEffect     = PersistentActorEffect.of[F](actorRefOf, eventSourcedOf)
      resources       = (actorEffect, probe).tupled
      result         <- resources.use { case (actorEffect, probe) =>
        persistentActorOf(actorEffect, probe, receiveTimeout.get)
      }
    } yield result
  }


  private def `recover with empty state`[F[_]: Concurrent: Timer: ToFuture: FromFuture: ToTry](
    actorSystem: ActorSystem
  ): F[Unit] = {
    val actorRefOf = ActorRefOf.fromActorRefFactory[F](actorSystem)

    type S = Int
    type C = Any
    type E = Any

    val recovery1 = Recovery(toSequenceNr = Int.MaxValue.toLong)

    def eventSourcedOf(
      startedDeferred: Deferred[F, Unit],
      stoppedDeferred: Deferred[F, Unit]
    ) =
      EventSourcedOf.const[F, S, E, C] {
        val eventSourced: EventSourced[F, S, E, C] = new EventSourced[F, S, E, C] {

          def eventSourcedId = EventSourcedId("0")

          def pluginIds = PluginIds.Empty

          def recovery = recovery1

          def start = {
            val started = RecoveryStarted[F, S, E, C] { (_, _) =>
              val recovering: Recovering[F, S, E, C] = new Recovering[F, S, E, C] {

                def replay = Replay.empty[F, E].pure[Resource[F, *]]

                def completed(
                  seqNr: SeqNr,
                  journaller: Journaller[F, E],
                  snapshotter: Snapshotter[F, S]
                ) = {
                  startedDeferred
                    .complete(())
                    .toResource
                    .as(Receive.empty[F, C])
                }
              }
              recovering.pure[Resource[F, *]]
            }
            Resource
              .make(().pure[F]) { _ => stoppedDeferred.complete(()) }
              .as(started)
          }
        }
        eventSourced.pure[F]
    }

    for {
      started        <- Deferred[F, Unit]
      stopped        <- Deferred[F, Unit]
      actions        <- Ref[F].of(List.empty[Action[S, C, E]])
      eventSourcedOf <- InstrumentEventSourced(actions, eventSourcedOf(started, stopped))
        .typeless(_.castM[F, S], _.pure[F], _.pure[F])
        .pure[F]
      actorEffect     = PersistentActorEffect.of(actorRefOf, eventSourcedOf)
      _              <- actorEffect.use { _ => started.get }
      _              <- stopped.get
      actions        <- actions.get
      _               = actions.reverse shouldEqual List(
        Action.Created(EventSourcedId("0"), recovery1, PluginIds.Empty),
        Action.Started,
        Action.RecoveryAllocated(None),
        Action.ReceiveAllocated(0L),
        Action.ReceiveReleased,
        Action.RecoveryReleased,
        Action.Released)
    } yield {}
  }


  private def `recover from snapshot`[F[_]: Concurrent: Timer: ToFuture: FromFuture: ToTry](
    actorSystem: ActorSystem
  ): F[Unit] = {
    val actorRefOf = ActorRefOf.fromActorRefFactory[F](actorSystem)

    type S = Int
    type C = Any
    type E = Int

    def eventSourcedOf(
      startedDeferred: Deferred[F, Unit],
      stoppedDeferred: Deferred[F, Unit]
    ): EventSourcedOf[F, S, E, C] = {
      EventSourcedOf.const[F, S, E, C] {
        val eventSourced: EventSourced[F, S, E, C] = new EventSourced[F, S, E, C] {

          def eventSourcedId = EventSourcedId("1")

          def pluginIds = PluginIds.Empty

          def recovery = Recovery()

          def start = {
            val started = RecoveryStarted.const[F, S, E, C] {
              val recovering: Recovering[F, S, E, C] = new Recovering[F, S, E, C] {

                def replay = Replay.empty[F, E].pure[Resource[F, *]]

                def completed(
                  seqNr: SeqNr,
                  journaller: Journaller[F, E],
                  snapshotter: Snapshotter[F, S]
                ) = {
                  val receive = for {
                    seqNr <- journaller.append(Events.of(0)).flatten
                    _     <- snapshotter.save(seqNr, 1).flatten
                    _     <- startedDeferred.complete(())
                  } yield {
                    Receive.empty[F, C]
                  }
                  receive.toResource
                }
              }

              recovering.pure[Resource[F, *]]
            }

            Resource
              .make(().pure[F]) { _ => stoppedDeferred.complete(()) }
              .as(started)
          }
        }
        eventSourced.pure[F]
      }
    }

    def actions = for {
      started        <- Deferred[F, Unit]
      stopped        <- Deferred[F, Unit]
      actions        <- Ref[F].of(List.empty[Action[S, C, E]])
      eventSourcedOf <- InstrumentEventSourced(actions, eventSourcedOf(started, stopped))
        .typeless(_.castM[F, S], _.castM[F, E], _.pure[F])
        .pure[F]
      actorEffect     = PersistentActorEffect.of(actorRefOf, eventSourcedOf)
      _              <- actorEffect.use { _ => started.get }
      _              <- stopped.get
      actions        <- actions.get
    } yield {
      actions.reverse
    }

    for {
      saveSnapshot <- actions
      _ = saveSnapshot shouldEqual List(
        Action.Created(EventSourcedId("1"), akka.persistence.Recovery(), PluginIds.Empty),
        Action.Started,
        Action.RecoveryAllocated(none),
        Action.AppendEvents(Events.of(0L)),
        Action.AppendEventsOuter,
        Action.AppendEventsInner(1),
        Action.SaveSnapshot(1, 1),
        Action.SaveSnapshotOuter,
        Action.SaveSnapshotInner,
        Action.ReceiveAllocated(0L),
        Action.ReceiveReleased,
        Action.RecoveryReleased,
        Action.Released)
      recover <- actions
      _ = recover shouldEqual List(
        Action.Created(EventSourcedId("1"), akka.persistence.Recovery(), PluginIds.Empty),
        Action.Started,
        Action.RecoveryAllocated(SnapshotOffer(SnapshotMetadata(1, Instant.ofEpochMilli(0)), 1).some),
        Action.AppendEvents(Events.of(0L)),
        Action.AppendEventsOuter,
        Action.AppendEventsInner(2),
        Action.SaveSnapshot(2, 1),
        Action.SaveSnapshotOuter,
        Action.SaveSnapshotInner,
        Action.ReceiveAllocated(1L),
        Action.ReceiveReleased,
        Action.RecoveryReleased,
        Action.Released)
    } yield {}
  }


  private def `recover from snapshot with deleted events`[F[_]: Concurrent: Timer: ToFuture: FromFuture: ToTry](
    actorSystem: ActorSystem
  ): F[Unit] = {
    val actorRefOf = ActorRefOf.fromActorRefFactory[F](actorSystem)

    type S = Int
    type C = Any
    type E = Int

    def eventSourcedOf(
      startedDeferred: Deferred[F, Unit],
      stoppedDeferred: Deferred[F, Unit]
    ): EventSourcedOf[F, S, E, C] = {
      _: ActorCtx[F] => {
        val eventSourced: EventSourced[F, S, E, C] = new EventSourced[F, S, E, C] {

          def eventSourcedId = EventSourcedId("6")

          def pluginIds = PluginIds.Empty

          def recovery = Recovery()

          def start = {
            val started = RecoveryStarted.const[F, S, E, C] {
              val recovering: Recovering[F, S, E, C] = new Recovering[F, S, E, C] {

                def replay = Replay.empty[F, E].pure[Resource[F, *]]

                def completed(
                  seqNr: SeqNr,
                  journaller: Journaller[F, E],
                  snapshotter: Snapshotter[F, S]
                ) = {
                  val receive = for {
                    seqNr <- journaller.append(Events.of(0)).flatten
                    _     <- snapshotter.save(seqNr, 1).flatten
                    seqNr <- journaller.append(Events.of(1)).flatten
                    _     <- journaller.deleteTo(seqNr).flatten
                    _     <- startedDeferred.complete(())
                  } yield {
                    Receive.empty[F, C]
                  }
                  receive.toResource
                }
              }

              recovering.pure[Resource[F, *]]
            }

            Resource
              .make(().pure[F]) { _ => stoppedDeferred.complete(()) }
              .as(started)
          }
        }
        eventSourced.pure[F]
      }
    }

    def actions = for {
      started        <- Deferred[F, Unit]
      stopped        <- Deferred[F, Unit]
      actions        <- Ref[F].of(List.empty[Action[S, C, E]])
      eventSourcedOf <- InstrumentEventSourced(actions, eventSourcedOf(started, stopped))
        .typeless(_.castM[F, S], _.castM[F, E], _.pure[F])
        .pure[F]
      actorEffect     = PersistentActorEffect.of(actorRefOf, eventSourcedOf)
      _              <- actorEffect.use { _ => started.get }
      _              <- stopped.get
      actions        <- actions.get
    } yield {
      actions.reverse
    }

    for {
      saveSnapshot <- actions
      _ = saveSnapshot shouldEqual List(
        Action.Created(EventSourcedId("6"), akka.persistence.Recovery(), PluginIds.Empty),
        Action.Started,
        Action.RecoveryAllocated(none),
        Action.AppendEvents(Events.of(0L)),
        Action.AppendEventsOuter,
        Action.AppendEventsInner(1),
        Action.SaveSnapshot(1, 1),
        Action.SaveSnapshotOuter,
        Action.SaveSnapshotInner,
        Action.AppendEvents(Events.of(1L)),
        Action.AppendEventsOuter,
        Action.AppendEventsInner(2),
        Action.DeleteEventsTo(2),
        Action.DeleteEventsToOuter,
        Action.DeleteEventsToInner,
        Action.ReceiveAllocated(0L),
        Action.ReceiveReleased,
        Action.RecoveryReleased,
        Action.Released)
      recover <- actions
      _ = recover shouldEqual List(
        Action.Created(EventSourcedId("6"), akka.persistence.Recovery(), PluginIds.Empty),
        Action.Started,
        Action.RecoveryAllocated(SnapshotOffer(SnapshotMetadata(1, Instant.ofEpochMilli(0)), 1).some),
        Action.AppendEvents(Events.of(0L)),
        Action.AppendEventsOuter,
        Action.AppendEventsInner(3),
        Action.SaveSnapshot(3, 1),
        Action.SaveSnapshotOuter,
        Action.SaveSnapshotInner,
        Action.AppendEvents(Events.of(1L)),
        Action.AppendEventsOuter,
        Action.AppendEventsInner(4),
        Action.DeleteEventsTo(4),
        Action.DeleteEventsToOuter,
        Action.DeleteEventsToInner,
        Action.ReceiveAllocated(2L),
        Action.ReceiveReleased,
        Action.RecoveryReleased,
        Action.Released)
    } yield {}
  }


  private def `recover from events`[F[_]: Concurrent: Timer: ToFuture: FromFuture: ToTry](
    actorSystem: ActorSystem
  ): F[Unit] = {
    val actorRefOf = ActorRefOf.fromActorRefFactory[F](actorSystem)

    type S = Int
    type C = Any
    type E = Int

    def eventSourcedOf(
      startedDeferred: Deferred[F, Unit],
      stoppedDeferred: Deferred[F, Unit]
    ): EventSourcedOf[F, S, E, C] = {
      _: ActorCtx[F] => {
        val eventSourced: EventSourced[F, S, E, C] = new EventSourced[F, S, E, C] {

          def eventSourcedId = EventSourcedId("2")

          def pluginIds = PluginIds.Empty

          def recovery = Recovery()

          def start = {
            val started = RecoveryStarted.const[F, S, E, C] {
              val recovering: Recovering[F, S, E, C] = new Recovering[F, S, E, C] {

                def replay = Replay.empty[F, E].pure[Resource[F, *]]

                def completed(
                  seqNr: SeqNr,
                  journaller: Journaller[F, E],
                  snapshotter: Snapshotter[F, S]
                ) = {
                  val receive = for {
                    _ <- journaller.append(Events.batched(Nel.of(0, 1), Nel.of(2))).flatten
                    _ <- startedDeferred.complete(())
                  } yield {
                    Receive.empty[F, C]
                  }
                  receive.toResource
                }
              }

              recovering.pure[Resource[F, *]]
            }

            Resource
              .make(().pure[F]) { _ => stoppedDeferred.complete(()) }
              .as(started)
          }
        }
        eventSourced.pure[F]
      }
    }


    def actions = for {
      started        <- Deferred[F, Unit]
      stopped        <- Deferred[F, Unit]
      actions        <- Ref[F].of(List.empty[Action[S, C, E]])
      eventSourcedOf <- InstrumentEventSourced(actions, eventSourcedOf(started, stopped))
        .typeless(_.castM[F, S], _.castM[F, E], _.pure[F])
        .pure[F]
      actorEffect     = PersistentActorEffect.of(actorRefOf, eventSourcedOf)
      _              <- actorEffect.use { _ => started.get }
      _              <- stopped.get
      actions        <- actions.get
    } yield {
      actions.reverse
    }

    for {
      appendEvents <- actions
      _ = appendEvents shouldEqual List(
        Action.Created(EventSourcedId("2"), akka.persistence.Recovery(), PluginIds.Empty),
        Action.Started,
        Action.RecoveryAllocated(none),
        Action.AppendEvents(Events.batched(Nel.of(0, 1), Nel.of(2))),
        Action.AppendEventsOuter,
        Action.AppendEventsInner(3),
        Action.ReceiveAllocated(0L),
        Action.ReceiveReleased,
        Action.RecoveryReleased,
        Action.Released)
      recover <- actions
      _ = recover shouldEqual List(
        Action.Created(EventSourcedId("2"), akka.persistence.Recovery(), PluginIds.Empty),
        Action.Started,
        Action.RecoveryAllocated(none),
        Action.ReplayAllocated,
        Action.Replayed(0, 1),
        Action.Replayed(1, 2),
        Action.Replayed(2, 3),
        Action.ReplayReleased,
        Action.AppendEvents(Events.batched(Nel.of(0, 1), Nel.of(2))),
        Action.AppendEventsOuter,
        Action.AppendEventsInner(6),
        Action.ReceiveAllocated(3L),
        Action.ReceiveReleased,
        Action.RecoveryReleased,
        Action.Released)
    } yield {}
  }


  private def `recover from events with deleted snapshot`[F[_]: Concurrent: Timer: ToFuture: FromFuture: ToTry](
    actorSystem: ActorSystem
  ): F[Unit] = {
    val actorRefOf = ActorRefOf.fromActorRefFactory[F](actorSystem)

    type S = Int
    type C = Any
    type E = Int

    def eventSourcedOf(
      startedDeferred: Deferred[F, Unit],
      stoppedDeferred: Deferred[F, Unit]
    ): EventSourcedOf[F, S, E, C] = {
      _: ActorCtx[F] => {
        val eventSourced: EventSourced[F, S, E, C] = new EventSourced[F, S, E, C] {

          def eventSourcedId = EventSourcedId("7")

          def pluginIds = PluginIds.Empty

          def recovery = Recovery()

          def start = {
            val started = RecoveryStarted.const[F, S, E, C] {
              val recovering: Recovering[F, S, E, C] = new Recovering[F, S, E, C] {

                def replay = Replay.empty[F, E].pure[Resource[F, *]]

                def completed(
                  seqNr: SeqNr,
                  journaller: Journaller[F, E],
                  snapshotter: Snapshotter[F, S]
                ) = {
                  val receive = for {
                    seqNr <- journaller.append(Events.of(0)).flatten
                    _     <- snapshotter.save(seqNr, 1).flatten
                    _     <- journaller.append(Events.of(1)).flatten
                    _     <- snapshotter.delete(seqNr).flatten
                    _     <- startedDeferred.complete(())
                  } yield {
                    Receive.empty[F, C]
                  }
                  receive.toResource
                }
              }

              recovering.pure[Resource[F, *]]
            }

            Resource
              .make(().pure[F]) { _ => stoppedDeferred.complete(()) }
              .as(started)
          }
        }
        eventSourced.pure[F]
      }
    }


    def actions = for {
      started        <- Deferred[F, Unit]
      stopped        <- Deferred[F, Unit]
      actions        <- Ref[F].of(List.empty[Action[S, C, E]])
      eventSourcedOf <- InstrumentEventSourced(actions, eventSourcedOf(started, stopped))
        .typeless(_.castM[F, S], _.castM[F, E], _.pure[F])
        .pure[F]
      actorEffect     = PersistentActorEffect.of(actorRefOf, eventSourcedOf)
      _              <- actorEffect.use { _ => started.get }
      _              <- stopped.get
      actions        <- actions.get
    } yield {
      actions.reverse
    }

    for {
      appendEvents <- actions
      _ = appendEvents shouldEqual List(
        Action.Created(EventSourcedId("7"), akka.persistence.Recovery(), PluginIds.Empty),
        Action.Started,
        Action.RecoveryAllocated(none),
        Action.AppendEvents(Events.of(0)),
        Action.AppendEventsOuter,
        Action.AppendEventsInner(1),
        Action.SaveSnapshot(1, 1),
        Action.SaveSnapshotOuter,
        Action.SaveSnapshotInner,
        Action.AppendEvents(Events.of(1)),
        Action.AppendEventsOuter,
        Action.AppendEventsInner(2),
        Action.DeleteSnapshot(1),
        Action.DeleteSnapshotOuter,
        Action.DeleteSnapshotInner,
        Action.ReceiveAllocated(0L),
        Action.ReceiveReleased,
        Action.RecoveryReleased,
        Action.Released)
      recover <- actions
      _ = recover shouldEqual List(
        Action.Created(EventSourcedId("7"), akka.persistence.Recovery(), PluginIds.Empty),
        Action.Started,
        Action.RecoveryAllocated(none),
        Action.ReplayAllocated,
        Action.Replayed(0, 1),
        Action.Replayed(1, 2),
        Action.ReplayReleased,
        Action.AppendEvents(Events.of(0)),
        Action.AppendEventsOuter,
        Action.AppendEventsInner(3),
        Action.SaveSnapshot(3, 1),
        Action.SaveSnapshotOuter,
        Action.SaveSnapshotInner,
        Action.AppendEvents(Events.of(1)),
        Action.AppendEventsOuter,
        Action.AppendEventsInner(4),
        Action.DeleteSnapshot(3),
        Action.DeleteSnapshotOuter,
        Action.DeleteSnapshotInner,
        Action.ReceiveAllocated(2L),
        Action.ReceiveReleased,
        Action.RecoveryReleased,
        Action.Released)
    } yield {}
  }


  private def `recover from snapshot and events`[F[_]: Concurrent: Timer: ToFuture: FromFuture: ToTry](
    actorSystem: ActorSystem
  ): F[Unit] = {
    val actorRefOf = ActorRefOf.fromActorRefFactory[F](actorSystem)

    type S = Int
    type C = Any
    type E = Int

    def eventSourcedOf(
      startedDeferred: Deferred[F, Unit],
      stoppedDeferred: Deferred[F, Unit]
    ): EventSourcedOf[F, S, E, C] = {
      _: ActorCtx[F] => {
        val eventSourced: EventSourced[F, S, E, C] = new EventSourced[F, S, E, C] {

          def eventSourcedId = EventSourcedId("3")

          def pluginIds = PluginIds.Empty

          def recovery = Recovery()

          def start = {
            val started = RecoveryStarted.const[F, S, E, C] {
              val recovering: Recovering[F, S, E, C] = new Recovering[F, S, E, C] {

                def replay = Replay.empty[F, E].pure[Resource[F, *]]

                def completed(
                  seqNr: SeqNr,
                  journaller: Journaller[F, E],
                  snapshotter: Snapshotter[F, S]
                ) = {
                  val receive = for {
                    seqNr <- journaller.append(Events.of(0)).flatten
                    _     <- snapshotter.save(seqNr, 1).flatten
                    _     <- journaller.append(Events.of(1)).flatten
                    _     <- startedDeferred.complete(())
                  } yield {
                    Receive.empty[F, C]
                  }

                  receive.toResource
                }
              }

              recovering.pure[Resource[F, *]]
            }

            Resource
              .make(().pure[F]) { _ => stoppedDeferred.complete(()) }
              .as(started)
          }
        }
        eventSourced.pure[F]
      }
    }

    def actions = for {
      started        <- Deferred[F, Unit]
      stopped        <- Deferred[F, Unit]
      actions        <- Ref[F].of(List.empty[Action[S, C, E]])
      eventSourcedOf <- InstrumentEventSourced(actions, eventSourcedOf(started, stopped))
        .typeless(_.castM[F, S], _.castM[F, E], _.pure[F])
        .pure[F]
      actorEffect     = PersistentActorEffect.of(actorRefOf, eventSourcedOf)
      _              <- actorEffect.use { _ => started.get }
      _              <- stopped.get
      actions        <- actions.get
    } yield {
      actions.reverse
    }

    for {
      write <- actions
      _ = write shouldEqual List(
        Action.Created(EventSourcedId("3"), akka.persistence.Recovery(), PluginIds.Empty),
        Action.Started,
        Action.RecoveryAllocated(none),
        Action.AppendEvents(Events.of(0)),
        Action.AppendEventsOuter,
        Action.AppendEventsInner(1),
        Action.SaveSnapshot(1, 1),
        Action.SaveSnapshotOuter,
        Action.SaveSnapshotInner,
        Action.AppendEvents(Events.of(1)),
        Action.AppendEventsOuter,
        Action.AppendEventsInner(2),
        Action.ReceiveAllocated(0L),
        Action.ReceiveReleased,
        Action.RecoveryReleased,
        Action.Released)
      recover <- actions
      _ = recover shouldEqual List(
        Action.Created(EventSourcedId("3"), akka.persistence.Recovery(), PluginIds.Empty),
        Action.Started,
        Action.RecoveryAllocated(SnapshotOffer(SnapshotMetadata(1, Instant.ofEpochMilli(0)), 1).some),
        Action.ReplayAllocated,
        Action.Replayed(1, 2),
        Action.ReplayReleased,
        Action.AppendEvents(Events.of(0)),
        Action.AppendEventsOuter,
        Action.AppendEventsInner(3),
        Action.SaveSnapshot(3, 1),
        Action.SaveSnapshotOuter,
        Action.SaveSnapshotInner,
        Action.AppendEvents(Events.of(1)),
        Action.AppendEventsOuter,
        Action.AppendEventsInner(4),
        Action.ReceiveAllocated(2L),
        Action.ReceiveReleased,
        Action.RecoveryReleased,
        Action.Released)
    } yield {}
  }


  private def `start stops`[F[_]: Concurrent: Timer: ToFuture: FromFuture: ToTry](
    actorSystem: ActorSystem
  ): F[Unit] = {
    val actorRefOf = ActorRefOf.fromActorRefFactory[F](actorSystem)

    type S = Any
    type C = Any
    type E = Any

    def eventSourcedOf(
      delay: F[Unit],
      stopped: Deferred[F, Unit]
    ): EventSourcedOf[F, S, E, C] = {
      actorCtx => {
        val eventSourced: EventSourced[F, S, E, C] = new EventSourced[F, S, E, C] {

          def eventSourcedId = EventSourcedId("10")

          def pluginIds = PluginIds.Empty

          def recovery = Recovery()

          def start = {
            Resource
              .make(delay productR actorCtx.stop) { _ => stopped.complete(()) }
              .as(RecoveryStarted.empty[F, S, E, C])
          }
        }
        eventSourced.pure[F]
      }
    }

    for {
      d0             <- Deferred[F, Unit]
      d1             <- Deferred[F, Unit]
      delay           = d0.complete(()) *> d1.get
      stopped        <- Deferred[F, Unit]
      actions        <- Ref[F].of(List.empty[Action[S, C, E]])
      eventSourcedOf <- InstrumentEventSourced(actions, eventSourcedOf(delay, stopped)).pure[F]
      actorEffect     = PersistentActorEffect.of(actorRefOf, eventSourcedOf)
      actorEffect    <- actorEffect.allocated.map { case (actorEffect, _) => actorEffect }
      _              <- Probe.of(actorRefOf).use { probe =>
        for {
          _          <- d0.get
          terminated <- probe.watch(actorEffect.toUnsafe)
          _          <- d1.complete(())
          _          <- terminated
        } yield {}
      }
      _              <- stopped.get
      actions        <- actions.get
      _               = actions.reverse shouldEqual List(
        Action.Created(EventSourcedId("10"), akka.persistence.Recovery(), PluginIds.Empty),
        Action.Started,
        Action.RecoveryAllocated(none),
        Action.ReceiveAllocated(0),
        Action.ReceiveReleased,
        Action.RecoveryReleased,
        Action.Released)
    } yield {}
  }


  private def `recoveryStarted stops`[F[_]: Concurrent: Timer: ToFuture: FromFuture: ToTry](
    actorSystem: ActorSystem
  ): F[Unit] = {
    val actorRefOf = ActorRefOf.fromActorRefFactory[F](actorSystem)

    type S = Any
    type C = Any
    type E = Any

    def eventSourcedOf(
      lock: Deferred[F, Unit],
      stopped: Deferred[F, Unit]
    ): EventSourcedOf[F, S, E, C] = {
      actorCtx => {
        val eventSourced: EventSourced[F, S, E, C] = new EventSourced[F, S, E, C] {

          def eventSourcedId = EventSourcedId("4")

          def pluginIds = PluginIds.Empty

          def recovery = Recovery()

          def start = {
            val started = RecoveryStarted[F, S, E, C] { (_, _) =>
              Resource
                .make(lock.get productR actorCtx.stop) { _ => stopped.complete(()) }
                .as(Recovering.empty[F, S, E, C])
            }
            started.pure[Resource[F, *]]
          }
        }
        eventSourced.pure[F]
      }
    }

    for {
      lock           <- Deferred[F, Unit]
      stopped        <- Deferred[F, Unit]
      actions        <- Ref[F].of(List.empty[Action[S, C, E]])
      eventSourcedOf <- InstrumentEventSourced(actions, eventSourcedOf(lock, stopped)).pure[F]
      actorEffect     = PersistentActorEffect.of(actorRefOf, eventSourcedOf)
      actorEffect    <- actorEffect.allocated.map { case (actorEffect, _) => actorEffect }
      _              <- Probe.of(actorRefOf).use { probe =>
        for {
          terminated <- probe.watch(actorEffect.toUnsafe)
          _          <- lock.complete(())
          _          <- terminated
        } yield {}
      }
      _              <- stopped.get
      actions        <- actions.get
      _               = actions.reverse shouldEqual List(
        Action.Created(EventSourcedId("4"), akka.persistence.Recovery(), PluginIds.Empty),
        Action.Started,
        Action.RecoveryAllocated(none),
        Action.ReceiveAllocated(0),
        Action.ReceiveReleased,
        Action.RecoveryReleased,
        Action.Released)
    } yield {}
  }


  private def `recoveryCompleted stops`[F[_]: Concurrent: Timer: ToFuture: FromFuture: ToTry](
    actorSystem: ActorSystem
  ): F[Unit] = {
    val actorRefOf = ActorRefOf.fromActorRefFactory[F](actorSystem)

    type S = Unit
    type C = Any
    type E = Unit

    def eventSourcedOf(
      lock: Deferred[F, Unit],
      stopped: Deferred[F, Unit]
    ): EventSourcedOf[F, S, E, C] = {
      actorCtx => {
        val eventSourced: EventSourced[F, S, E, C] = new EventSourced[F, S, E, C] {

          def eventSourcedId = EventSourcedId("5")

          def pluginIds = PluginIds.Empty

          def recovery = Recovery()

          def start = {
            val started = RecoveryStarted[F, S, E, C] { (_, _) =>

              val recovering: Recovering[F, S, E, C] = new Recovering[F, S, E, C] {

                def replay = Replay.empty[F, E].pure[Resource[F, *]]

                def completed(
                  seqNr: SeqNr,
                  journaller: Journaller[F, E],
                  snapshotter: Snapshotter[F, S]
                ) = {
                  Resource
                    .make(lock.get productR actorCtx.stop) { _ => stopped.complete(()) }
                    .as(Receive.empty[F, C])
                }
              }
              recovering.pure[Resource[F, *]]
            }
            started.pure[Resource[F, *]]
          }
        }
        eventSourced.pure[F]
      }
    }

    for {
      lock           <- Deferred[F, Unit]
      stopped        <- Deferred[F, Unit]
      actions        <- Ref[F].of(List.empty[Action[S, C, E]])
      eventSourcedOf <- InstrumentEventSourced(actions, eventSourcedOf(lock, stopped))
        .typeless(_.castM[F, S], _.castM[F, E], _.pure[F])
        .pure[F]
      actorEffect     = PersistentActorEffect.of(actorRefOf, eventSourcedOf)
      actorEffect    <- actorEffect.allocated.map { case (actorEffect, _) => actorEffect }
      _              <- Probe.of(actorRefOf).use { probe =>
        for {
          terminated <- probe.watch(actorEffect.toUnsafe)
          _          <- lock.complete(())
          _          <- terminated
        } yield {}
      }
      _              <- stopped.get
      actions        <- actions.get
      _               = actions.reverse shouldEqual List(
        Action.Created(EventSourcedId("5"), akka.persistence.Recovery(), PluginIds.Empty),
        Action.Started,
        Action.RecoveryAllocated(none),
        Action.ReceiveAllocated(0),
        Action.ReceiveReleased,
        Action.RecoveryReleased,
        Action.Released)
    } yield {}
  }


  private def `append many`[F[_]: Concurrent: Timer: ToFuture: FromFuture: ToTry](
    actorSystem: ActorSystem
  ): F[Unit] = {
    val actorRefOf = ActorRefOf.fromActorRefFactory[F](actorSystem)

    type S = Boolean
    type C = Any
    type E = SeqNr

    val events = Nel.fromListUnsafe((1L to 1000L).toList)

    def eventSourcedOf(
      startedDeferred: Deferred[F, Unit],
      stoppedDeferred: Deferred[F, Unit]
    ): EventSourcedOf[F, S, E, C] = {
      _: ActorCtx[F] => {
        val eventSourced: EventSourced[F, S, E, C] = new EventSourced[F, S, E, C] {

          def eventSourcedId = EventSourcedId("8")

          def pluginIds = PluginIds.Empty

          def recovery = Recovery()

          def start = {
            val started = RecoveryStarted[F, S, E, C] { (_, _) =>

              for {
                stateRef <- Ref[F].of(true).toResource
              } yield {
                new Recovering[F, S, E, C] {

                  val replay = Replay.const[F, E](stateRef.set(false)).pure[Resource[F, *]]

                  def completed(
                    seqNr: SeqNr,
                    journaller: Journaller[F, E],
                    snapshotter: Snapshotter[F, S]
                  ) = {

                    def append: F[Unit] = {
                      for {
                        state  <- stateRef.get
                        result <- if (state) {
                          events
                            .traverse { event => journaller.append(Events.of(event)) }
                            .flatMap { _.foldMapM { _.void } }
                        } else {
                          ().pure[F]
                        }
                      } yield result
                    }

                    val receive = for {
                      _ <- append
                      _ <- startedDeferred.complete(())
                    } yield {
                      Receive.empty[F, C]
                    }
                    receive.toResource
                  }
                }
              }
            }
            Resource
              .make(().pure[F]) { _ => stoppedDeferred.complete(()) }
              .as(started)
          }
        }
        eventSourced.pure[F]
      }
    }

    def actions = for {
      started        <- Deferred[F, Unit]
      stopped        <- Deferred[F, Unit]
      actions        <- Ref[F].of(List.empty[Action[S, C, E]])
      eventSourcedOf <- InstrumentEventSourced(actions, eventSourcedOf(started, stopped))
        .typeless(_.castM[F, S], _.castM[F, E], _.pure[F])
        .pure[F]
      actorEffect     = PersistentActorEffect.of(actorRefOf, eventSourcedOf)
      _              <- actorEffect.use { _ => started.get }
      _              <- stopped.get
      actions        <- actions.get
    } yield {
      actions.reverse
    }

    def appends: Nel[Action[S, C, E]] = {
      val appendEvents: Nel[Action[S, C, E]] = events.flatMap { event =>
        Nel.of(
          Action.AppendEvents(Events.of(event)),
          Action.AppendEventsOuter)
      }
      val appendEventsInner: Nel[Action[S, C, E]] = events.map { event =>
        Action.AppendEventsInner(event)
      }

      appendEvents ::: appendEventsInner
    }

    def replayed: Nel[Action[S, C, E]] = {
      events.map { event => Action.Replayed(event, event) }
    }

    for {
      a <- actions
      _  = a shouldEqual List(
        Action.Created(EventSourcedId("8"), akka.persistence.Recovery(), PluginIds.Empty),
        Action.Started,
        Action.RecoveryAllocated(none)) ++
      appends.toList ++
      List(
        Action.ReceiveAllocated(0),
        Action.ReceiveReleased,
        Action.RecoveryReleased,
        Action.Released)
      a <- actions
      _  = a shouldEqual List(
        Action.Created(EventSourcedId("8"), akka.persistence.Recovery(), PluginIds.Empty),
        Action.Started,
        Action.RecoveryAllocated(none),
        Action.ReplayAllocated) ++
      replayed.toList ++
      List(
        Action.ReplayReleased,
        Action.ReceiveAllocated(events.last),
        Action.ReceiveReleased,
        Action.RecoveryReleased,
        Action.Released)
    } yield {}
  }


  private def setReceiveTimeout[F[_]: Concurrent: Timer: ToFuture: FromFuture: ToTry](actorSystem: ActorSystem) = {

    val actorRefOf = ActorRefOf.fromActorRefFactory[F](actorSystem)

    type S = Boolean
    type C = Any
    type E = SeqNr

    def eventSourcedOf(timedOut: Deferred[F, Unit]): EventSourcedOf[F, S, E, C] = {
      actorCtx => {
        for {
          _ <- actorCtx.setReceiveTimeout(10.millis)
        } yield {
          new EventSourced[F, S, E, C] {

            def eventSourcedId = EventSourcedId("9")

            def pluginIds = PluginIds.Empty

            def recovery = Recovery()

            def start = {
              for {
                _ <- actorCtx.setReceiveTimeout(10.millis).toResource
              } yield {
                RecoveryStarted[F, S, E, C] { (_, _) =>
                  for {
                    _ <- actorCtx.setReceiveTimeout(10.millis).toResource
                  } yield {
                    new Recovering[F, S, E, C] {

                      val replay = {
                        Replay
                          .empty[F, E]
                          .pure[Resource[F, *]]
                      }

                      def completed(
                        seqNr: SeqNr,
                        journaller: Journaller[F, E],
                        snapshotter: Snapshotter[F, S]
                      ) = {
                        for {
                          _ <- actorCtx.setReceiveTimeout(10.millis).toResource
                        } yield {
                          Receive[F, C] { (a, _) =>
                            a match {
                              case ReceiveTimeout => timedOut.complete(()).as(true)
                              case _              => false.pure[F]
                            }
                          }
                        }
                      }
                    }
                  }
                }
              }
            }
          }
        }
      }
    }

    for {
      timedOut       <- Deferred[F, Unit]
      eventSourcedOf <- eventSourcedOf(timedOut)
        .typeless(_.castM[F, S], _.castM[F, E], _.pure[F])
        .pure[F]
      result          = PersistentActorEffect.of(actorRefOf, eventSourcedOf)
      result         <- result.use { _ => timedOut.get}
    } yield result
  }
}