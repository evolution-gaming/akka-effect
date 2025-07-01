package com.evolutiongaming.akkaeffect.persistence

import akka.actor.{ActorIdentity, ActorRef, ActorSystem, Identify}
import akka.persistence.Recovery
import akka.testkit.TestActors
import cats.data.NonEmptyList as Nel
import cats.effect.*
import cats.effect.kernel.Ref
import cats.effect.syntax.all.*
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import com.evolutiongaming.akkaeffect.IOSuite.*
import com.evolutiongaming.akkaeffect.persistence.InstrumentEventSourced.Action
import com.evolutiongaming.akkaeffect.testkit.Probe
import com.evolutiongaming.akkaeffect.{ActorSuite, *}
import com.evolutiongaming.catshelper.CatsHelper.*
import com.evolutiongaming.catshelper.{FromFuture, ToFuture, ToTry}
import org.scalatest.funsuite.AsyncFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import scala.concurrent.duration.*
import scala.reflect.ClassTag

class PersistentActorOfTest extends AsyncFunSuite with ActorSuite with Matchers {

  test("all") {
    `persistentActorOf`[IO](actorSystem).run()
  }

  test("recover with empty state") {
    `recover with empty state`[IO](actorSystem).run()
  }

  test("recover from snapshot") {
    `recover from snapshot`[IO](actorSystem).run()
  }

  test("recover from snapshot with deleted events") {
    `recover from snapshot with deleted events`[IO](actorSystem).run()
  }

  test("recover from events") {
    `recover from events`[IO](actorSystem).run()
  }

  test("recover from events with deleted snapshot") {
    `recover from events with deleted snapshot`[IO](actorSystem).run()
  }

  test("recover from snapshot and events") {
    `recover from snapshot and events`[IO](actorSystem).run()
  }

  test("start stops") {
    `start stops`[IO](actorSystem).run()
  }

  test("recoveryStarted stops") {
    `recoveryStarted stops`[IO](actorSystem).run()
  }

  test("recoveryCompleted stops") {
    `recoveryCompleted stops`[IO](actorSystem).run()
  }

  test("append many") {
    `append many`[IO](actorSystem).run()
  }

  test("setReceiveTimeout") {
    setReceiveTimeout[IO](actorSystem).run()
  }

  private def `persistentActorOf`[F[_]: Async: ToFuture: FromFuture: ToTry](
    actorSystem: ActorSystem,
  ): F[Unit] = {

    type State = Int
    type Event = String

    sealed trait Cmd

    object Cmd {
      final case object Inc                               extends Cmd
      final case object Stop                              extends Cmd
      final case class WithCtx[A](f: ActorCtx[F] => F[A]) extends Cmd
    }

    def eventSourcedOf(
      receiveTimeout: F[Unit],
    ): EventSourcedOf[F, Resource[F, RecoveryStarted[F, State, Event, Receive[F, Envelope[Any], Boolean]]]] =
      EventSourcedOf[F] { actorCtx =>
        val recoveryStarted =
          RecoveryStarted
            .const {
              Recovering[State]
                .apply1 {
                  Replay.empty[F, Event].pure[Resource[F, *]]
                } { recoveringCtx =>
                  for {
                    stateRef <- Ref[F].of(0).toResource
                  } yield Receive[Envelope[Cmd]] { envelope =>
                    val reply = Reply.fromActorRef[F](to = envelope.from, from = actorCtx.self)

                    envelope.msg match {
                      case a: Cmd.WithCtx[_] =>
                        for {
                          a <- a.f(actorCtx)
                          _ <- reply(a)
                        } yield false

                      case Cmd.Inc =>
                        for {
                          seqNr  <- recoveringCtx.journaller.append(Events.of("a")).flatten
                          _      <- stateRef.update(_ + 1)
                          state  <- stateRef.get
                          result <- recoveringCtx.snapshotter.save(seqNr, state)
                          seqNr  <- recoveringCtx.journaller
                            .append(Events.batched(Nel.of("b"), Nel.of("c", "d")))
                            .flatten
                          _ <- result
                          _ <- stateRef.update(_ + 1)
                          _ <- reply(seqNr)
                        } yield false

                      case Cmd.Stop =>
                        for {
                          _ <- reply("stopping")
                        } yield true
                    }
                  } {
                    for {
                      _ <- actorCtx.setReceiveTimeout(Duration.Inf)
                      _ <- receiveTimeout
                    } yield false
                  }
                    .contramapM[Envelope[Any]] { envelope =>
                      envelope.msg
                        .castM[F, Cmd]
                        .map(a => envelope.copy(msg = a))
                    }
                }
                .pure[Resource[F, *]]
            }
            .pure[Resource[F, *]]
        EventSourced(EventSourcedId("id"), value = recoveryStarted).pure[F]
      }

    def persistentActorOf(
      actorRef: ActorEffect[F, Any, Any],
      probe: Probe[F],
      receiveTimeout: F[Unit],
    ): F[Unit] = {

      val timeout = 10.seconds

      def withCtx[A: ClassTag](f: ActorCtx[F] => F[A]): F[A] =
        for {
          a <- actorRef.ask(Cmd.WithCtx(f), timeout)
          a <- a
          a <- a.castM[F, A]
        } yield a

      for {
        terminated0 <- probe.watch(actorRef.toUnsafe)
        dispatcher  <- withCtx(_.executor.pure[F])
        _           <- Sync[F].delay(dispatcher.toString shouldEqual "Dispatcher[akka.actor.default-dispatcher]")
        a           <- withCtx { ctx =>
          ActorRefOf
            .fromActorRefFactory[F](ctx.actorRefFactory)
            .apply(TestActors.blackholeProps, "child".some)
            .allocated
        }
        (child0, childRelease) = a
        terminated1           <- probe.watch(child0)
        children              <- withCtx(_.children)
        _                     <- Sync[F].delay(children should contain(child0))
        child                  = withCtx(_.child("child"))
        child1                <- child
        _                     <- Sync[F].delay(child1 shouldEqual child0.some)
        _                     <- childRelease
        _                     <- terminated1
        child1                <- child
        _                     <- Sync[F].delay(child1 shouldEqual none[ActorRef])
        children              <- withCtx(_.children)
        _                     <- Sync[F].delay(children should not contain child0)
        identity              <- actorRef.ask(Identify("id"), timeout).flatten
        identity              <- identity.castM[F, ActorIdentity]
        _                     <- withCtx(_.setReceiveTimeout(1.millis))
        _                     <- receiveTimeout
        _                     <- Sync[F].delay(identity shouldEqual ActorIdentity("id", actorRef.toUnsafe.some))
        seqNr                 <- actorRef.ask(Cmd.Inc, timeout).flatten
        _                      = seqNr shouldEqual 4
        a                     <- actorRef.ask(Cmd.Stop, timeout).flatten
        _                      = a shouldEqual "stopping"
        _                     <- terminated0
      } yield {}
    }

    for {
      receiveTimeout <- Deferred[F, Unit]
      eventSourcedOf <- eventSourcedOf(receiveTimeout.complete(()).void)
        .typeless(_.castM[F, State], _.castM[F, Event], _.pure[F])
        .convert[Any, Any, Receive[F, Envelope[Any], Boolean]](
          _.pure[F],
          _.pure[F],
          _.pure[F],
          _.pure[F],
          _.pure[Resource[F, *]],
        )
        .pure[F]
      actorRefOf  = ActorRefOf.fromActorRefFactory[F](actorSystem)
      probe       = Probe.of[F](actorRefOf)
      actorEffect = PersistentActorEffect.of[F](actorRefOf, eventSourcedOf)
      resources   = (actorEffect, probe).tupled
      result     <- resources.use {
        case (actorEffect, probe) =>
          persistentActorOf(actorEffect, probe, receiveTimeout.get)
      }
    } yield result
  }

  private def `recover with empty state`[F[_]: Async: ToFuture: FromFuture: ToTry](
    actorSystem: ActorSystem,
  ): F[Unit] = {
    val actorRefOf = ActorRefOf.fromActorRefFactory[F](actorSystem)

    type S = Int
    type C = Any
    type E = Any

    val recovery1 = Recovery(toSequenceNr = Int.MaxValue.toLong)

    def eventSourcedOf(
      startedDeferred: Deferred[F, Unit],
      stoppedDeferred: Deferred[F, Unit],
    ) =
      EventSourcedOf.const {
        val recoveryStarted = {
          val started = RecoveryStarted[S] { (_, _) =>
            Recovering
              .const[S] {
                Replay.empty[F, E].pure[Resource[F, *]]
              } {
                startedDeferred
                  .complete(())
                  .toResource
                  .as(Receive.const[Envelope[C]](false.pure[F]))
              }
              .pure[Resource[F, *]]
          }
          Resource
            .make(().pure[F])(_ => stoppedDeferred.complete(()).void)
            .as(started)
        }
        EventSourced(EventSourcedId("0"), recovery1, value = recoveryStarted).pure[F]
      }

    for {
      started        <- Deferred[F, Unit]
      stopped        <- Deferred[F, Unit]
      actions        <- Ref[F].of(List.empty[Action[S, C, E]])
      eventSourcedOf <- InstrumentEventSourced(actions, eventSourcedOf(started, stopped))
        .typeless(_.castM[F, S], _.pure[F], _.pure[F])
        .pure[F]
      actorEffect = PersistentActorEffect.of(actorRefOf, eventSourcedOf)
      _          <- actorEffect.use(_ => started.get)
      _          <- stopped.get
      actions    <- actions.get
      _           = actions.reverse shouldEqual List(
        Action.Created(EventSourcedId("0"), recovery1, PluginIds.Empty),
        Action.Started,
        Action.RecoveryAllocated(0L, None),
        Action.ReceiveAllocated(0L),
        Action.ReceiveReleased,
        Action.RecoveryReleased,
        Action.Released,
      )
    } yield {}
  }

  private def `recover from snapshot`[F[_]: Async: ToFuture: FromFuture: ToTry](
    actorSystem: ActorSystem,
  ): F[Unit] = {
    val actorRefOf = ActorRefOf.fromActorRefFactory[F](actorSystem)

    type S = Int
    type C = Any
    type E = Int

    def eventSourcedOf(
      startedDeferred: Deferred[F, Unit],
      stoppedDeferred: Deferred[F, Unit],
    ) =
      EventSourcedOf.const {
        val recoveryStarted = {
          val started = RecoveryStarted.const {
            Recovering[S]
              .apply1 {
                Replay.empty[F, E].pure[Resource[F, *]]
              } { recoveringCtx =>
                val receive = for {
                  seqNr <- recoveringCtx.journaller.append(Events.of(0)).flatten
                  _     <- recoveringCtx.snapshotter.save(seqNr, 1).flatten
                  _     <- startedDeferred.complete(())
                } yield Receive.const[Envelope[C]](false.pure[F])
                receive.toResource
              }
              .pure[Resource[F, *]]
          }

          Resource
            .make(().pure[F])(_ => stoppedDeferred.complete(()).void)
            .as(started)
        }
        EventSourced(EventSourcedId("1"), value = recoveryStarted).pure[F]
      }

    def actions = for {
      started        <- Deferred[F, Unit]
      stopped        <- Deferred[F, Unit]
      actions        <- Ref[F].of(List.empty[Action[S, C, E]])
      eventSourcedOf <- InstrumentEventSourced(actions, eventSourcedOf(started, stopped))
        .typeless(_.castM[F, S], _.castM[F, E], _.pure[F])
        .pure[F]
      actorEffect = PersistentActorEffect.of(actorRefOf, eventSourcedOf)
      _          <- actorEffect.use(_ => started.get)
      _          <- stopped.get
      actions    <- actions.get
    } yield actions.reverse

    for {
      saveSnapshot <- actions
      _             = saveSnapshot shouldEqual List(
        Action.Created(EventSourcedId("1"), akka.persistence.Recovery(), PluginIds.Empty),
        Action.Started,
        Action.RecoveryAllocated(0L, none),
        Action.AppendEvents(Events.of(0L)),
        Action.AppendEventsOuter,
        Action.AppendEventsInner(1),
        Action.SaveSnapshot(1, 1),
        Action.SaveSnapshotOuter,
        Action.SaveSnapshotInner,
        Action.ReceiveAllocated(0L),
        Action.ReceiveReleased,
        Action.RecoveryReleased,
        Action.Released,
      )
      recover <- actions
      _        = recover shouldEqual List(
        Action.Created(EventSourcedId("1"), akka.persistence.Recovery(), PluginIds.Empty),
        Action.Started,
        Action.RecoveryAllocated(1L, SnapshotOffer(SnapshotMetadata(1, Instant.ofEpochMilli(0)), 1).some),
        Action.AppendEvents(Events.of(0L)),
        Action.AppendEventsOuter,
        Action.AppendEventsInner(2),
        Action.SaveSnapshot(2, 1),
        Action.SaveSnapshotOuter,
        Action.SaveSnapshotInner,
        Action.ReceiveAllocated(1L),
        Action.ReceiveReleased,
        Action.RecoveryReleased,
        Action.Released,
      )
    } yield {}
  }

  private def `recover from snapshot with deleted events`[F[_]: Async: ToFuture: FromFuture: ToTry](
    actorSystem: ActorSystem,
  ): F[Unit] = {
    val actorRefOf = ActorRefOf.fromActorRefFactory[F](actorSystem)

    type S = Int
    type C = Any
    type E = Int

    def eventSourcedOf(
      startedDeferred: Deferred[F, Unit],
      stoppedDeferred: Deferred[F, Unit],
    ) =
      EventSourcedOf.const {
        val recoveryStarted = {
          val started = RecoveryStarted.const {
            Recovering[S]
              .apply1 {
                Replay.empty[F, E].pure[Resource[F, *]]
              } { recoveringCtx =>
                val receive = for {
                  seqNr <- recoveringCtx.journaller.append(Events.of(0)).flatten
                  _     <- recoveringCtx.snapshotter.save(seqNr, 1).flatten
                  seqNr <- recoveringCtx.journaller.append(Events.of(1)).flatten
                  _     <- recoveringCtx.journaller.deleteTo(seqNr).flatten
                  _     <- startedDeferred.complete(())
                } yield Receive.const[Envelope[C]](false.pure[F])
                receive.toResource
              }
              .pure[Resource[F, *]]
          }

          Resource
            .make(().pure[F])(_ => stoppedDeferred.complete(()).void)
            .as(started)
        }
        EventSourced(EventSourcedId("6"), value = recoveryStarted).pure[F]
      }

    def actions = for {
      started        <- Deferred[F, Unit]
      stopped        <- Deferred[F, Unit]
      actions        <- Ref[F].of(List.empty[Action[S, C, E]])
      eventSourcedOf <- InstrumentEventSourced(actions, eventSourcedOf(started, stopped))
        .typeless(_.castM[F, S], _.castM[F, E], _.pure[F])
        .pure[F]
      actorEffect = PersistentActorEffect.of(actorRefOf, eventSourcedOf)
      _          <- actorEffect.use(_ => started.get)
      _          <- stopped.get
      actions    <- actions.get
    } yield actions.reverse

    for {
      saveSnapshot <- actions
      _             = saveSnapshot shouldEqual List(
        Action.Created(EventSourcedId("6"), akka.persistence.Recovery(), PluginIds.Empty),
        Action.Started,
        Action.RecoveryAllocated(0L, none),
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
        Action.Released,
      )
      recover <- actions
      _        = recover shouldEqual List(
        Action.Created(EventSourcedId("6"), akka.persistence.Recovery(), PluginIds.Empty),
        Action.Started,
        Action.RecoveryAllocated(1L, SnapshotOffer(SnapshotMetadata(1, Instant.ofEpochMilli(0)), 1).some),
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
        Action.Released,
      )
    } yield {}
  }

  private def `recover from events`[F[_]: Async: ToFuture: FromFuture: ToTry](
    actorSystem: ActorSystem,
  ): F[Unit] = {
    val actorRefOf = ActorRefOf.fromActorRefFactory[F](actorSystem)

    type S = Int
    type C = Any
    type E = Int

    def eventSourcedOf(
      startedDeferred: Deferred[F, Unit],
      stoppedDeferred: Deferred[F, Unit],
    ) =
      EventSourcedOf.const {
        val recoveryStarted = {
          val started = RecoveryStarted.const {
            Recovering[S]
              .apply1 {
                Replay.empty[F, E].pure[Resource[F, *]]
              } { recoveringCtx =>
                val receive = for {
                  _ <- recoveringCtx.journaller.append(Events.batched(Nel.of(0, 1), Nel.of(2))).flatten
                  _ <- startedDeferred.complete(())
                } yield Receive.const[Envelope[C]](false.pure[F])
                receive.toResource
              }
              .pure[Resource[F, *]]
          }

          Resource
            .make(().pure[F])(_ => stoppedDeferred.complete(()).void)
            .as(started)
        }
        EventSourced(EventSourcedId("2"), value = recoveryStarted).pure[F]
      }

    def actions = for {
      started        <- Deferred[F, Unit]
      stopped        <- Deferred[F, Unit]
      actions        <- Ref[F].of(List.empty[Action[S, C, E]])
      eventSourcedOf <- InstrumentEventSourced(actions, eventSourcedOf(started, stopped))
        .typeless(_.castM[F, S], _.castM[F, E], _.pure[F])
        .pure[F]
      actorEffect = PersistentActorEffect.of(actorRefOf, eventSourcedOf)
      _          <- actorEffect.use(_ => started.get)
      _          <- stopped.get
      actions    <- actions.get
    } yield actions.reverse

    for {
      appendEvents <- actions
      _             = appendEvents shouldEqual List(
        Action.Created(EventSourcedId("2"), akka.persistence.Recovery(), PluginIds.Empty),
        Action.Started,
        Action.RecoveryAllocated(0L, none),
        Action.AppendEvents(Events.batched(Nel.of(0, 1), Nel.of(2))),
        Action.AppendEventsOuter,
        Action.AppendEventsInner(3),
        Action.ReceiveAllocated(0L),
        Action.ReceiveReleased,
        Action.RecoveryReleased,
        Action.Released,
      )
      recover <- actions
      _        = recover shouldEqual List(
        Action.Created(EventSourcedId("2"), akka.persistence.Recovery(), PluginIds.Empty),
        Action.Started,
        Action.RecoveryAllocated(0L, none),
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
        Action.Released,
      )
    } yield {}
  }

  private def `recover from events with deleted snapshot`[F[_]: Async: ToFuture: FromFuture: ToTry](
    actorSystem: ActorSystem,
  ): F[Unit] = {
    val actorRefOf = ActorRefOf.fromActorRefFactory[F](actorSystem)

    type S = Int
    type C = Any
    type E = Int

    def eventSourcedOf(
      startedDeferred: Deferred[F, Unit],
      stoppedDeferred: Deferred[F, Unit],
    ) =
      EventSourcedOf.const {
        val recoveryStarted = {
          val started = RecoveryStarted.const {
            Recovering[S]
              .apply1 {
                Replay.empty[F, E].pure[Resource[F, *]]
              } { recoveringCtx =>
                val receive = for {
                  seqNr <- recoveringCtx.journaller.append(Events.of(0)).flatten
                  _     <- recoveringCtx.snapshotter.save(seqNr, 1).flatten
                  _     <- recoveringCtx.journaller.append(Events.of(1)).flatten
                  _     <- recoveringCtx.snapshotter.delete(seqNr).flatten
                  _     <- startedDeferred.complete(())
                } yield Receive.const[Envelope[C]](false.pure[F])
                receive.toResource
              }
              .pure[Resource[F, *]]
          }

          Resource
            .make(().pure[F])(_ => stoppedDeferred.complete(()).void)
            .as(started)
        }
        EventSourced(EventSourcedId("7"), value = recoveryStarted).pure[F]
      }

    def actions = for {
      started        <- Deferred[F, Unit]
      stopped        <- Deferred[F, Unit]
      actions        <- Ref[F].of(List.empty[Action[S, C, E]])
      eventSourcedOf <- InstrumentEventSourced(actions, eventSourcedOf(started, stopped))
        .typeless(_.castM[F, S], _.castM[F, E], _.pure[F])
        .pure[F]
      actorEffect = PersistentActorEffect.of(actorRefOf, eventSourcedOf)
      _          <- actorEffect.use(_ => started.get)
      _          <- stopped.get
      actions    <- actions.get
    } yield actions.reverse

    for {
      appendEvents <- actions
      _             = appendEvents shouldEqual List(
        Action.Created(EventSourcedId("7"), akka.persistence.Recovery(), PluginIds.Empty),
        Action.Started,
        Action.RecoveryAllocated(0L, none),
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
        Action.Released,
      )
      recover <- actions
      _        = recover shouldEqual List(
        Action.Created(EventSourcedId("7"), akka.persistence.Recovery(), PluginIds.Empty),
        Action.Started,
        Action.RecoveryAllocated(0L, none),
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
        Action.Released,
      )
    } yield {}
  }

  private def `recover from snapshot and events`[F[_]: Async: ToFuture: FromFuture: ToTry](
    actorSystem: ActorSystem,
  ): F[Unit] = {
    val actorRefOf = ActorRefOf.fromActorRefFactory[F](actorSystem)

    type S = Int
    type C = Any
    type E = Int

    def eventSourcedOf(
      startedDeferred: Deferred[F, Unit],
      stoppedDeferred: Deferred[F, Unit],
    ) =
      EventSourcedOf.const {
        val recoveryStarted = {
          val started = RecoveryStarted.const {
            Recovering[S]
              .apply1 {
                Replay.empty[F, E].pure[Resource[F, *]]
              } { recoveringCtx =>
                val receive = for {
                  seqNr <- recoveringCtx.journaller.append(Events.of(0)).flatten
                  _     <- recoveringCtx.snapshotter.save(seqNr, 1).flatten
                  _     <- recoveringCtx.journaller.append(Events.of(1)).flatten
                  _     <- startedDeferred.complete(())
                } yield Receive.const[Envelope[C]](false.pure[F])

                receive.toResource
              }
              .pure[Resource[F, *]]
          }

          Resource
            .make(().pure[F])(_ => stoppedDeferred.complete(()).void)
            .as(started)
        }
        EventSourced(EventSourcedId("3"), value = recoveryStarted).pure[F]
      }

    def actions = for {
      started        <- Deferred[F, Unit]
      stopped        <- Deferred[F, Unit]
      actions        <- Ref[F].of(List.empty[Action[S, C, E]])
      eventSourcedOf <- InstrumentEventSourced(actions, eventSourcedOf(started, stopped))
        .typeless(_.castM[F, S], _.castM[F, E], _.pure[F])
        .pure[F]
      actorEffect = PersistentActorEffect.of(actorRefOf, eventSourcedOf)
      _          <- actorEffect.use(_ => started.get)
      _          <- stopped.get
      actions    <- actions.get
    } yield actions.reverse

    for {
      write <- actions
      _      = write shouldEqual List(
        Action.Created(EventSourcedId("3"), akka.persistence.Recovery(), PluginIds.Empty),
        Action.Started,
        Action.RecoveryAllocated(0L, none),
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
        Action.Released,
      )
      recover <- actions
      _        = recover shouldEqual List(
        Action.Created(EventSourcedId("3"), akka.persistence.Recovery(), PluginIds.Empty),
        Action.Started,
        Action.RecoveryAllocated(1L, SnapshotOffer(SnapshotMetadata(1, Instant.ofEpochMilli(0)), 1).some),
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
        Action.Released,
      )
    } yield {}
  }

  private def `start stops`[F[_]: Async: ToFuture: FromFuture: ToTry](
    actorSystem: ActorSystem,
  ): F[Unit] = {
    val actorRefOf = ActorRefOf.fromActorRefFactory[F](actorSystem)

    type S = Any
    type C = Any
    type E = Any

    def eventSourcedOf(
      delay: F[Unit],
      stopped: Deferred[F, Unit],
    ) =
      EventSourcedOf[F] { actorCtx =>
        val recoveryStarted =
          Resource
            .make(delay productR actorCtx.stop)(_ => stopped.complete(()).void)
            .as {
              RecoveryStarted.const {
                Recovering[S]
                  .apply1 {
                    Replay
                      .empty[F, E]
                      .pure[Resource[F, *]]
                  } { _ =>
                    Receive
                      .const[Envelope[C]](false.pure[F])
                      .pure[Resource[F, *]]
                  }
                  .pure[Resource[F, *]]
              }
            }
        EventSourced(EventSourcedId("10"), value = recoveryStarted).pure[F]
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
          _          <- Temporal[F].sleep(10.millis)
          _          <- d1.complete(())
          _          <- terminated
        } yield {}
      }
      _       <- stopped.get
      actions <- actions.get
      _        = actions.reverse shouldEqual List(
        Action.Created(EventSourcedId("10"), akka.persistence.Recovery(), PluginIds.Empty),
        Action.Started,
        Action.RecoveryAllocated(0L, none),
        Action.ReceiveAllocated(0),
        Action.ReceiveReleased,
        Action.RecoveryReleased,
        Action.Released,
      )
    } yield {}
  }

  private def `recoveryStarted stops`[F[_]: Async: ToFuture: FromFuture: ToTry](
    actorSystem: ActorSystem,
  ): F[Unit] = {
    val actorRefOf = ActorRefOf.fromActorRefFactory[F](actorSystem)

    type S = Any
    type C = Any
    type E = Any

    def eventSourcedOf(
      lock: Deferred[F, Unit],
      stopped: Deferred[F, Unit],
    ) =
      EventSourcedOf[F] { actorCtx =>
        val recoveryStarted =
          RecoveryStarted
            .const {
              Resource
                .make(lock.get productR actorCtx.stop)(_ => stopped.complete(()).void)
                .as {
                  Recovering.const[S] {
                    Replay
                      .empty[F, E]
                      .pure[Resource[F, *]]
                  } {
                    Receive
                      .const[Envelope[C]](false.pure[F])
                      .pure[Resource[F, *]]
                  }
                }
            }
            .pure[Resource[F, *]]
        EventSourced(EventSourcedId("4"), value = recoveryStarted).pure[F]
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
      _       <- stopped.get
      _       <- Async[F].sleep(10.millis) // Make sure all actions are performed first
      actions <- actions.get
      _        = actions.reverse shouldEqual List(
        Action.Created(EventSourcedId("4"), akka.persistence.Recovery(), PluginIds.Empty),
        Action.Started,
        Action.RecoveryAllocated(0L, none),
        Action.ReceiveAllocated(0),
        Action.ReceiveReleased,
        Action.RecoveryReleased,
        Action.Released,
      )
    } yield {}
  }

  private def `recoveryCompleted stops`[F[_]: Async: ToFuture: FromFuture: ToTry](
    actorSystem: ActorSystem,
  ): F[Unit] = {
    val actorRefOf = ActorRefOf.fromActorRefFactory[F](actorSystem)

    type S = Unit
    type C = Any
    type E = Unit

    def eventSourcedOf(
      lock: Deferred[F, Unit],
      stopped: Deferred[F, Unit],
    ) =
      EventSourcedOf[F] { actorCtx =>
        val recoveryStarted =
          RecoveryStarted
            .const {
              Recovering
                .const[S] {
                  Replay
                    .empty[F, E]
                    .pure[Resource[F, *]]
                } {
                  Resource
                    .make(lock.get productR actorCtx.stop)(_ => stopped.complete(()).void)
                    .as(Receive.const[Envelope[C]](false.pure[F]))
                }
                .pure[Resource[F, *]]
            }
            .pure[Resource[F, *]]
        EventSourced(EventSourcedId("5"), value = recoveryStarted).pure[F]
      }

    for {
      lock           <- Deferred[F, Unit]
      stopped        <- Deferred[F, Unit]
      actions        <- Ref[F].of(List.empty[Action[S, C, E]])
      eventSourcedOf <- InstrumentEventSourced(actions, eventSourcedOf(lock, stopped))
        .typeless(_.castM[F, S], _.castM[F, E], _.pure[F])
        .pure[F]
      actorEffect  = PersistentActorEffect.of(actorRefOf, eventSourcedOf)
      actorEffect <- actorEffect.allocated.map { case (actorEffect, _) => actorEffect }
      _           <- Probe.of(actorRefOf).use { probe =>
        for {
          terminated <- probe.watch(actorEffect.toUnsafe)
          _          <- lock.complete(())
          _          <- terminated
        } yield {}
      }
      _       <- stopped.get
      _       <- Async[F].sleep(10.millis) // Make sure all actions are performed first
      actions <- actions.get
      _        = actions.reverse shouldEqual List(
        Action.Created(EventSourcedId("5"), akka.persistence.Recovery(), PluginIds.Empty),
        Action.Started,
        Action.RecoveryAllocated(0L, none),
        Action.ReceiveAllocated(0),
        Action.ReceiveReleased,
        Action.RecoveryReleased,
        Action.Released,
      )
    } yield {}
  }

  private def `append many`[F[_]: Async: ToFuture: FromFuture: ToTry](
    actorSystem: ActorSystem,
  ): F[Unit] = {
    val actorRefOf = ActorRefOf.fromActorRefFactory[F](actorSystem)

    type S = Boolean
    type C = Any
    type E = SeqNr

    val events = Nel.fromListUnsafe((1L to 1000L).toList)

    def eventSourcedOf(
      startedDeferred: Deferred[F, Unit],
      stoppedDeferred: Deferred[F, Unit],
    ) =
      EventSourcedOf.const {
        val recoveryStarted = {
          val started = RecoveryStarted.const {

            for {
              stateRef <- Ref[F].of(true).toResource
            } yield Recovering[S].apply1 {
              Replay.const[E](stateRef.set(false)).pure[Resource[F, *]]
            } { recoveringCtx =>
              def append: F[Unit] =
                for {
                  state  <- stateRef.get
                  result <-
                    if (state) {
                      events
                        .traverse { event =>
                          recoveringCtx.journaller.append(Events.of(event))
                        }
                        .flatMap(_.foldMapM(_.void))
                    } else {
                      ().pure[F]
                    }
                } yield result

              val receive = for {
                _ <- append
                _ <- startedDeferred.complete(())
              } yield Receive.const[Envelope[C]](false.pure[F])
              receive.toResource
            }
          }
          Resource
            .make(().pure[F])(_ => stoppedDeferred.complete(()).void)
            .as(started)
        }
        EventSourced(EventSourcedId("8"), value = recoveryStarted).pure[F]
      }

    def actions = for {
      started        <- Deferred[F, Unit]
      stopped        <- Deferred[F, Unit]
      actions        <- Ref[F].of(List.empty[Action[S, C, E]])
      eventSourcedOf <- InstrumentEventSourced(actions, eventSourcedOf(started, stopped))
        .typeless(_.castM[F, S], _.castM[F, E], _.pure[F])
        .pure[F]
      actorEffect = PersistentActorEffect.of(actorRefOf, eventSourcedOf)
      _          <- actorEffect.use(_ => started.get)
      _          <- stopped.get
      actions    <- actions.get
    } yield actions.reverse

    def appends: Nel[Action[S, C, E]] = {
      val appendEvents: Nel[Action[S, C, E]] = events.flatMap { event =>
        Nel.of(Action.AppendEvents(Events.of(event)), Action.AppendEventsOuter)
      }
      val appendEventsInner: Nel[Action[S, C, E]] = events.map { event =>
        Action.AppendEventsInner(event)
      }

      appendEvents ::: appendEventsInner
    }

    def replayed: Nel[Action[S, C, E]] =
      events.map(event => Action.Replayed(event, event))

    for {
      a <- actions
      _  = a shouldEqual List(
        Action.Created(EventSourcedId("8"), akka.persistence.Recovery(), PluginIds.Empty),
        Action.Started,
        Action.RecoveryAllocated(0L, none),
      ) ++
        appends.toList ++
        List(Action.ReceiveAllocated(0), Action.ReceiveReleased, Action.RecoveryReleased, Action.Released)
      a <- actions
      _  = a shouldEqual List(
        Action.Created(EventSourcedId("8"), akka.persistence.Recovery(), PluginIds.Empty),
        Action.Started,
        Action.RecoveryAllocated(0L, none),
        Action.ReplayAllocated,
      ) ++
        replayed.toList ++
        List(
          Action.ReplayReleased,
          Action.ReceiveAllocated(events.last),
          Action.ReceiveReleased,
          Action.RecoveryReleased,
          Action.Released,
        )
    } yield {}
  }

  private def setReceiveTimeout[F[_]: Async: ToFuture: FromFuture: ToTry](actorSystem: ActorSystem) = {

    val actorRefOf = ActorRefOf.fromActorRefFactory[F](actorSystem)

    type S = Boolean
    type C = Any
    type E = SeqNr

    def eventSourcedOf(timedOut: Deferred[F, Unit]) =
      EventSourcedOf[F] { actorCtx =>
        for {
          _ <- actorCtx.setReceiveTimeout(10.millis)
        } yield {
          val recoveryStarted =
            for {
              _ <- actorCtx.setReceiveTimeout(10.millis).toResource
            } yield RecoveryStarted.const {
              for {
                _ <- actorCtx.setReceiveTimeout(10.millis).toResource
              } yield Recovering.const[S] {
                Replay
                  .empty[F, E]
                  .pure[Resource[F, *]]
              } {
                for {
                  _ <- actorCtx.setReceiveTimeout(10.millis).toResource
                } yield Receive[Envelope[C]] { _ =>
                  false.pure[F]
                } {
                  timedOut.complete(()).as(true)
                }
              }
            }
          EventSourced(EventSourcedId("9"), value = recoveryStarted)
        }
      }

    for {
      timedOut       <- Deferred[F, Unit]
      eventSourcedOf <- eventSourcedOf(timedOut)
        .typeless(_.castM[F, S], _.castM[F, E], _.pure[F])
        .pure[F]
      result  = PersistentActorEffect.of(actorRefOf, eventSourcedOf)
      result <- result.use(_ => timedOut.get)
    } yield result
  }
}
