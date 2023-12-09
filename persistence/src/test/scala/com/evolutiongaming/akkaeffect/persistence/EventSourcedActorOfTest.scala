package com.evolutiongaming.akkaeffect.persistence

import akka.actor.Props
import cats.effect.unsafe.implicits.global
import cats.effect.{Async, IO, Ref, Resource}
import cats.syntax.all._
import com.evolutiongaming.akkaeffect._
import com.evolutiongaming.akkaeffect.persistence.InstrumentEventSourced.Action
import com.evolutiongaming.akkaeffect.testkit.Probe
import com.evolutiongaming.sstream.Stream
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.Instant
import scala.concurrent.duration._

class EventSourcedActorOfTest extends AnyWordSpec with ActorSuite with Matchers {

  type F[A] = IO[A]
  val F = Async[F]

  type S = Int
  type E = String
  type C = String

  type Receiving = Receive[F, Envelope[C], ActorOf.Stop]
  type Lifecycle = Resource[F, RecoveryStarted[F, S, E, Receiving]]

  val actorRefOf = ActorRefOf.fromActorRefFactory[F](actorSystem)

  val timestamp = Instant.ofEpochMilli(0) // due to hardcoded value in InstrumentEventSourced #45
  val timeout   = 1.second

  "recover" when {

    val id = EventSourcedId("id #1")

    val eventSourcedOf: EventSourcedOf[F, Lifecycle] =
      EventSourcedOf.const {
        F.delay {
          EventSourced(
            eventSourcedId = id,
            value = RecoveryStarted
              .const {
                Recovering
                  .const[S] {
                    Replay.empty[F, E].pure[Resource[F, *]]
                  } {
                    Receive[Envelope[C]] { envelope =>
                      val reply =
                        Reply.fromActorRef[F](to = envelope.from, from = none)

                      envelope.msg match {
                        case "foo" => reply("bar").as(false)
                        case "bar" => reply("foo").as(false)
                        case "die" => F.pure(true)
                      }
                    } {
                      true.pure[F]
                    }.pure[Resource[F, *]]
                  }
                  .pure[Resource[F, *]]
              }
              .pure[Resource[F, *]]
          )
        }
      }

    "no snapshots and no events" should {

      val eventSourcedStoreOf: EventSourcedPersistenceOf[F, S, E] =
        EventSourcedPersistenceOf.const {
          EventSourcedPersistence.const(
            recovery = EventSourcedPersistence.Recovery.const(none, Stream.empty),
            journaller = Journaller.empty[F, E],
            snapshotter = Snapshotter.empty[F, S]
          )
        }

      "recover empty state" in {

        Probe
          .of(actorRefOf)
          .use { probe =>
            for {
              actions <- Ref[F].of(List.empty[Action[S, C, E]])
              eventSourcedOf <- InstrumentEventSourced(actions, eventSourcedOf)
                .pure[F]

              props = Props(
                EventSourcedActorOf.actor(eventSourcedOf, eventSourcedStoreOf)
              )
              actor      <- F.delay(actorSystem.actorOf(props))
              effect     <- ActorEffect.fromActor[F](actor).pure[F]
              terminated <- probe.watch(actor)

              _ <- effect.tell(akka.actor.ReceiveTimeout)
              _ <- terminated

              actions <- actions.get
              actions <- actions
                .map {
                  case Action.Received(m, _, s) => Action.Received(m, null, s)
                  case action                   => action
                }
                .reverse
                .pure[F]
              _ = actions shouldEqual List(
                Action.Created(id, akka.persistence.Recovery(), PluginIds()),
                Action.Started,
                Action.RecoveryAllocated(0L, none),
                Action.ReplayAllocated,
                Action.ReplayReleased,
                Action.ReceiveAllocated(0L),
                Action.Received(akka.actor.ReceiveTimeout, null, stop = true),
                Action.ReceiveReleased,
                Action.RecoveryReleased,
                Action.Released
              )
            } yield {}
          }
      }
    }

    "no snapshots and few events" should {

      val eventSourcedStoreOf: EventSourcedPersistenceOf[F, S, E] =
        EventSourcedPersistenceOf.const {
          EventSourcedPersistence.const(
            recovery = EventSourcedPersistence.Recovery.const(
              none,
              Stream.from[F, List, Event[E]](
                List(Event.const("first", 1L), Event.const("second", 2L))
              )
            ),
            journaller = Journaller.empty[F, E],
            snapshotter = Snapshotter.empty[F, S]
          )
        }

      "recover empty state" in {

        Probe
          .of(actorRefOf)
          .use { probe =>
            for {
              actions <- Ref[F].of(List.empty[Action[S, C, E]])
              eventSourcedOf <- InstrumentEventSourced(actions, eventSourcedOf)
                .pure[F]

              props = Props(
                EventSourcedActorOf.actor(eventSourcedOf, eventSourcedStoreOf)
              )
              actor      <- F.delay(actorSystem.actorOf(props))
              effect     <- ActorEffect.fromActor[F](actor).pure[F]
              terminated <- probe.watch(actor)

              _ <- effect.tell(akka.actor.ReceiveTimeout)
              _ <- terminated

              actions <- actions.get
              actions <- actions
                .map {
                  case Action.Received(m, _, s) => Action.Received(m, null, s)
                  case action                   => action
                }
                .reverse
                .pure[F]
              _ = actions shouldEqual List(
                Action.Created(id, akka.persistence.Recovery(), PluginIds()),
                Action.Started,
                Action.RecoveryAllocated(0L, none),
                Action.ReplayAllocated,
                Action.Replayed("first", 1L),
                Action.Replayed("second", 2L),
                Action.ReplayReleased,
                Action.ReceiveAllocated(0L),
                Action.Received(akka.actor.ReceiveTimeout, null, stop = true),
                Action.ReceiveReleased,
                Action.RecoveryReleased,
                Action.Released
              )
            } yield {}
          }
      }
    }

    "there's snapshot and no events" should {

      val eventSourcedStoreOf: EventSourcedPersistenceOf[F, S, E] =
        EventSourcedPersistenceOf.const {
          EventSourcedPersistence.const(
            recovery = EventSourcedPersistence.Recovery.const(
              Snapshot.const(0, Snapshot.Metadata(0L, timestamp)).some,
              Stream.empty
            ),
            journaller = Journaller.empty[F, E],
            snapshotter = Snapshotter.empty[F, S]
          )
        }

      "recover empty state" in {

        Probe
          .of(actorRefOf)
          .use { probe =>
            for {
              actions <- Ref[F].of(List.empty[Action[S, C, E]])
              eventSourcedOf <- InstrumentEventSourced(actions, eventSourcedOf)
                .pure[F]

              props = Props(
                EventSourcedActorOf.actor(eventSourcedOf, eventSourcedStoreOf)
              )
              actor      <- F.delay(actorSystem.actorOf(props))
              effect     <- ActorEffect.fromActor[F](actor).pure[F]
              terminated <- probe.watch(actor)

              _ <- effect.tell(akka.actor.ReceiveTimeout)
              _ <- terminated

              actions <- actions.get
              actions <- actions
                .map {
                  case Action.Received(m, _, s) => Action.Received(m, null, s)
                  case action                   => action
                }
                .reverse
                .pure[F]
              _ = actions shouldEqual List(
                Action.Created(id, akka.persistence.Recovery(), PluginIds()),
                Action.Started,
                Action.RecoveryAllocated(
                  0L,
                  SnapshotOffer(SnapshotMetadata(0L, timestamp), 0).some
                ),
                Action.ReplayAllocated,
                Action.ReplayReleased,
                Action.ReceiveAllocated(0L),
                Action.Received(akka.actor.ReceiveTimeout, null, stop = true),
                Action.ReceiveReleased,
                Action.RecoveryReleased,
                Action.Released
              )
            } yield {}
          }
      }
    }

    "there's snapshot and few event" should {

      val eventSourcedStoreOf: EventSourcedPersistenceOf[F, S, E] =
        EventSourcedPersistenceOf.const {
          EventSourcedPersistence.const(
            recovery = EventSourcedPersistence.Recovery.const(
              Snapshot.const(0, Snapshot.Metadata(0L, timestamp)).some,
              Stream.from[F, List, Event[E]](
                List(Event.const("first", 1L), Event.const("second", 2L))
              )
            ),
            journaller = Journaller.empty[F, E],
            snapshotter = Snapshotter.empty[F, S]
          )
        }

      "be successful" in {

        Probe
          .of(actorRefOf)
          .use { probe =>
            for {
              actions <- Ref[F].of(List.empty[Action[S, C, E]])
              eventSourcedOf <- InstrumentEventSourced(actions, eventSourcedOf)
                .pure[F]

              props = Props(
                EventSourcedActorOf.actor(eventSourcedOf, eventSourcedStoreOf)
              )
              actor      <- F.delay(actorSystem.actorOf(props))
              effect     <- ActorEffect.fromActor[F](actor).pure[F]
              terminated <- probe.watch(actor)

              bar <- effect.ask("foo", timeout).flatten
              _    = bar shouldEqual "bar"

              foo <- effect.ask("bar", timeout).flatten
              _    = foo shouldEqual "foo"

              _ <- effect.tell("die")
              _ <- terminated

              actions <- actions.get
              actions <- actions
                .map {
                  case Action.Received(m, _, s) => Action.Received(m, null, s)
                  case action                   => action
                }
                .reverse
                .pure[F]
              _ = actions shouldEqual List(
                Action.Created(id, akka.persistence.Recovery(), PluginIds()),
                Action.Started,
                Action.RecoveryAllocated(
                  0L,
                  SnapshotOffer(SnapshotMetadata(0L, timestamp), 0).some
                ),
                Action.ReplayAllocated,
                Action.Replayed("first", 1L),
                Action.Replayed("second", 2L),
                Action.ReplayReleased,
                Action.ReceiveAllocated(2L),
                Action.Received("foo", null, stop = false),
                Action.Received("bar", null, stop = false),
                Action.Received("die", null, stop = true),
                Action.ReceiveReleased,
                Action.RecoveryReleased,
                Action.Released
              )
            } yield {}
          }

      }

      "stop on akka.actor.ReceiveTimeout" in {
        Probe
          .of(actorRefOf)
          .use { probe =>
            for {
              actions <- Ref[F].of(List.empty[Action[S, C, E]])
              eventSourcedOf <- InstrumentEventSourced(actions, eventSourcedOf)
                .pure[F]

              props = Props(
                EventSourcedActorOf.actor(eventSourcedOf, eventSourcedStoreOf)
              )
              actor      <- F.delay(actorSystem.actorOf(props))
              effect     <- ActorEffect.fromActor[F](actor).pure[F]
              terminated <- probe.watch(actor)

              _ <- effect.tell(akka.actor.ReceiveTimeout)
              _ <- terminated

              actions <- actions.get
              actions <- actions
                .map {
                  case Action.Received(m, _, s) => Action.Received(m, null, s)
                  case action                   => action
                }
                .reverse
                .pure[F]
              _ = actions shouldEqual List(
                Action.Created(id, akka.persistence.Recovery(), PluginIds()),
                Action.Started,
                Action.RecoveryAllocated(
                  0L,
                  SnapshotOffer(SnapshotMetadata(0L, timestamp), 0).some
                ),
                Action.ReplayAllocated,
                Action.Replayed("first", 1L),
                Action.Replayed("second", 2L),
                Action.ReplayReleased,
                Action.ReceiveAllocated(2L),
                Action.ReceiveTimeout,
                Action.ReceiveReleased,
                Action.RecoveryReleased,
                Action.Released
              )
            } yield {}

          }
          .unsafeRunSync()
      }

    }

    "exception" should {

      "be raised from EventSourcedStoreOf[F].apply, ie on loading Akka plugins in EventSourcedStoreOf.fromAkka" in {
        val eventSourcedStoreOf: EventSourcedPersistenceOf[F, S, E] =
          _ => F.raiseError(new RuntimeException())

        Probe
          .of(actorRefOf)
          .use { probe =>
            for {
              actions <- Ref[F].of(List.empty[Action[S, C, E]])
              eventSourcedOf <- InstrumentEventSourced(actions, eventSourcedOf)
                .pure[F]

              props = Props(
                EventSourcedActorOf.actor(eventSourcedOf, eventSourcedStoreOf)
              )
              actor      <- F.delay(actorSystem.actorOf(props))
              effect     <- ActorEffect.fromActor[F](actor).pure[F]
              terminated <- probe.watch(actor)

              _ <- effect.tell(akka.actor.ReceiveTimeout)
              _ <- terminated

              actions <- actions.get
              actions <- actions
                .map {
                  case Action.Received(m, _, s) => Action.Received(m, null, s)
                  case action                   => action
                }
                .reverse
                .pure[F]
              _ = actions shouldEqual List(
                Action.Created(id, akka.persistence.Recovery(), PluginIds()),
                Action.Started,
                Action.Released
              )
            } yield {}

          }
          .unsafeRunSync()
      }

      "be raised from EventSourcedStore[F].recover, ie on loading snapshot" in {
        val eventSourcedStoreOf: EventSourcedPersistenceOf[F, S, E] =
          EventSourcedPersistenceOf.const {
            new EventSourcedPersistence[F, S, E] {
              override def recover = F.raiseError(new RuntimeException())

              override def journaller(seqNr: SeqNr) = Journaller.empty[F, E].pure[F]

              override def snapshotter = Snapshotter.empty[F, S].pure[F]
            }
          }

        Probe
          .of(actorRefOf)
          .use { probe =>
            for {
              actions <- Ref[F].of(List.empty[Action[S, C, E]])
              eventSourcedOf <- InstrumentEventSourced(actions, eventSourcedOf)
                .pure[F]

              props = Props(
                EventSourcedActorOf.actor(eventSourcedOf, eventSourcedStoreOf)
              )
              actor      <- F.delay(actorSystem.actorOf(props))
              effect     <- ActorEffect.fromActor[F](actor).pure[F]
              terminated <- probe.watch(actor)

              _ <- effect.tell(akka.actor.ReceiveTimeout)
              _ <- terminated

              actions <- actions.get
              actions <- actions
                .map {
                  case Action.Received(m, _, s) => Action.Received(m, null, s)
                  case action                   => action
                }
                .reverse
                .pure[F]
              _ = actions shouldEqual List(
                Action.Created(id, akka.persistence.Recovery(), PluginIds()),
                Action.Started,
                Action.Released
              )
            } yield {}

          }
          .unsafeRunSync()
      }

      "be raised on materialisation of Stream provided by Recovery[F].events', ie on loading events" in {
        val eventSourcedStoreOf: EventSourcedPersistenceOf[F, S, E] =
          EventSourcedPersistenceOf.const {
            EventSourcedPersistence.const(
              recovery = EventSourcedPersistence.Recovery
                .const(none, Stream.lift(F.raiseError(new RuntimeException()))),
              journaller = Journaller.empty[F, E],
              snapshotter = Snapshotter.empty[F, S]
            )
          }

        Probe
          .of(actorRefOf)
          .use { probe =>
            for {
              actions <- Ref[F].of(List.empty[Action[S, C, E]])
              eventSourcedOf <- InstrumentEventSourced(actions, eventSourcedOf)
                .pure[F]

              props = Props(
                EventSourcedActorOf.actor(eventSourcedOf, eventSourcedStoreOf)
              )
              actor      <- F.delay(actorSystem.actorOf(props))
              effect     <- ActorEffect.fromActor[F](actor).pure[F]
              terminated <- probe.watch(actor)

              _ <- effect.tell(akka.actor.ReceiveTimeout)
              _ <- terminated

              actions <- actions.get
              actions <- actions
                .map {
                  case Action.Received(m, _, s) => Action.Received(m, null, s)
                  case action                   => action
                }
                .reverse
                .pure[F]
              _ = actions shouldEqual List(
                Action.Created(id, akka.persistence.Recovery(), PluginIds()),
                Action.Started,
                Action.RecoveryAllocated(0L, none),
                Action.ReplayAllocated,
                Action.ReplayReleased,
                Action.RecoveryReleased,
                Action.Released
              )
            } yield {}

          }
          .unsafeRunSync()
      }

    }
  }

}
