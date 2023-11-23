package com.evolutiongaming.akkaeffect.persistence

import akka.actor.Props
import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Ref, Resource}
import cats.syntax.all._
import com.evolutiongaming.akkaeffect._
import com.evolutiongaming.akkaeffect.persistence.InstrumentEventSourced.Action
import com.evolutiongaming.akkaeffect.testkit.Probe
import com.evolutiongaming.sstream.Stream
import org.scalatest.funsuite.AnyFunSuiteLike
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import scala.concurrent.duration._

class EventSourcedActorOfTest
    extends AnyFunSuiteLike
    with ActorSuite
    with Matchers {

  type F[A] = IO[A]

  type S = Int
  type E = String
  type C = String

  type Receiving = Receive[F, Envelope[C], ActorOf.Stop]
  type Lifecycle = Resource[F, RecoveryStarted[F, S, E, Receiving]]

  val actorRefOf = ActorRefOf.fromActorRefFactory[F](actorSystem)

  val timestamp = Instant.ofEpochMilli(0) // trust me, its for InstrumentEventSourced backward compatibility
  val timeout = 1.second

  test("recover") {

    val id = EventSourcedId("id #1")

    val eventSourcedOf: EventSourcedOf[F, Lifecycle] = EventSourcedOf.const {
      IO.delay {
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
                      case "die" => IO.pure(true)
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

    val eventSourcedStoreOf: EventSourcedStoreOf[F, S, E] =
      EventSourcedStoreOf.const {
        EventSourcedStore.const(
          recovery_ = Recovery.const(
            Snapshot.const(0, Snapshot.Metadata(0L, timestamp)).some,
            Stream.single(Event.const("first", 1L))
          ),
          journaller_ = Journaller.empty[F, E],
          snapshotter_ = Snapshotter.empty[F, S],
        )
      }

    val actions = Ref.unsafe[F, List[Action[S, C, E]]](List.empty)
    val esOf = InstrumentEventSourced(actions, eventSourcedOf)
    def actor = EventSourcedActorOf.actor(esOf, eventSourcedStoreOf)

    Probe
      .of(actorRefOf)
      .use { probe =>
        for {
          actor <- IO.delay { actorSystem.actorOf(Props(actor)) }
          effect <- ActorEffect.fromActor[F](actor).pure[F]
          terminated <- probe.watch(actor)

          bar <- effect.ask("foo", timeout).flatten
          _ = bar shouldEqual "bar"

          foo <- effect.ask("bar", timeout).flatten
          _ = foo shouldEqual "foo"

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
            Action.ReplayReleased,
            Action.ReceiveAllocated(1L),
            Action.Received("foo", null, stop = false),
            Action.Received("bar", null, stop = false),
            Action.Received("die", null, stop = true),
            Action.ReceiveReleased,
            Action.RecoveryReleased,
            Action.Released
          )
        } yield {}
      }
      .unsafeRunSync()
  }

}
