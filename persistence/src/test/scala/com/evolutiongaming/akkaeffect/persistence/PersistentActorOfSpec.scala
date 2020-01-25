package com.evolutiongaming.akkaeffect.persistence

import akka.actor.{ActorIdentity, ActorRef, ActorSystem, Identify, ReceiveTimeout}
import akka.testkit.TestActors
import cats.data.{NonEmptyList => Nel}
import cats.effect.concurrent.{Deferred, Ref}
import cats.effect.{Concurrent, IO, Resource, Sync}
import cats.implicits._
import com.evolutiongaming.akkaeffect.IOSuite._
import com.evolutiongaming.akkaeffect._
import com.evolutiongaming.catshelper.{FromFuture, ToFuture, ToTry}
import org.scalatest.funsuite.AsyncFunSuite
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._
import scala.reflect.ClassTag
import scala.util.control.NoStackTrace

class PersistentActorOfSpec extends AsyncFunSuite with ActorSuite with Matchers {
  import PersistentActorOfSpec._

  test("PersistentActor") {
    `persistentActorOf`[IO](actorSystem).run()
  }

  private def `persistentActorOf`[F[_] : Concurrent : ToFuture : FromFuture : ToTry](
    actorSystem: ActorSystem
  ): F[Unit] = {

    def persistenceSetupOf(receiveTimeout: F[Unit]): PersistenceSetupOf[F, Any, Any, Any, Any] = {
      (ctx: ActorCtx[F, Any, Any]) => {

        val persistenceSetup = new PersistenceSetup[F, State, Any, Event] {

          def persistenceId = "persistenceId"

          def onRecoveryStarted(
            offer: Option[SnapshotOffer[State]],
            journaller: Journaller[F, Event],
            snapshotter: Snapshotter[F, State]
          ) = {

            val recovering: Recovering[F, State, Any, Event] = new Recovering[F, State, Any, Event] {

              def initial = 0

              val replay = new Replay[F, State, Event] {

                def apply(state: State, event: Event, seqNr: SeqNr) = {
                  println(s"replay state: $state, event: $event, seqNr: $seqNr")
                  state.pure[F]
                }
              }

              def onRecoveryCompleted(state: State, seqNr: SeqNr) = {
                println(s"onRecoveryCompleted state: $state, seqNr: $seqNr")

                receive(ctx, state, receiveTimeout, journaller, snapshotter)
              }
            }
            recovering.pure[F]
          }
        }

        implicit val anyToEvent = Conversion.cast[F, Any, Event]

        implicit val anyToState = Conversion.cast[F, Any, State]

        persistenceSetup.untype.pure[Resource[F, *]]
      }
    }


    for {
      receiveTimeout     <- Deferred[F, Unit]
      persistenceSetupOf <- persistenceSetupOf(receiveTimeout.complete(())).pure[F]
      actorRefOf          = ActorRefOf[F](actorSystem)
      probe               = Probe.of[F](actorSystem)
      actorEffect         = PersistentActorEffect.of[F](actorRefOf, persistenceSetupOf)
      resources           = (actorEffect, probe).tupled
      result             <- resources.use { case (actorEffect, probe) => persistentActorOf(actorEffect, probe, receiveTimeout.get, actorRefOf) }
    } yield result
  }

  def persistentActorOf[F[_] : Concurrent : ToFuture : FromFuture : ToTry](
    actorRef: ActorEffect[F, Any, Any],
    probe: Probe[F],
    receiveTimeout: F[Unit],
    actorRefOf: ActorRefOf[F]
  ): F[Unit] = {

    def snapshotAndEvents: F[(Option[State], List[Event])] = {

      def persistenceSetupOf(
        eventsDeferred: Deferred[F, List[Event]],
        snapshotDeferred: Deferred[F, Option[State]]
      ): PersistenceSetupOf[F, Any, Any, Any, Any] = {
        (_: ActorCtx[F, Any, Any]) => {

          val setup = new PersistenceSetup[F, State, Any, Event] {

            def persistenceId = "persistenceId"

            def onRecoveryStarted(
              offer: Option[SnapshotOffer[State]],
              journaller: Journaller[F, Event],
              snapshotter: Snapshotter[F, State]
            ): F[Recovering[F, State, Any, Event]] = {

              val snapshot = for {offer <- offer} yield offer.snapshot

              for {
                _      <- snapshotDeferred.complete(snapshot)
                events <- Ref[F].of(List.empty[Event])
              } yield {
                new Recovering[F, State, Any, Event] {

                  def initial = 0

                  val replay = new Replay[F, State, Event] {

                    def apply(state: State, event: Event, seqNr: SeqNr) = {
                      println(s"replay state: $state, event: $event, seqNr: $seqNr")
                      events.update { event :: _ }.as(state)
                    }
                  }

                  def onRecoveryCompleted(state: State, seqNr: SeqNr) = {
                    println(s"onRecoveryCompleted state: $state, seqNr: $seqNr")

                    // TODO stop actor ?
                    for {
                      events <- events.get
                      _      <- eventsDeferred.complete(events)
                    } yield {
                      Receive.empty[F, Any, Any]
                    }
                  }
                }
              }
            }
          }

          implicit val anyToEvent = Conversion.cast[F, Any, Event]

          implicit val anyToState = Conversion.cast[F, Any, State]

          setup.untype.pure[Resource[F, *]]
        }
      }

      for {
        events             <- Deferred[F, List[Event]]
        snapshot           <- Deferred[F, Option[State]]
        persistenceSetupOf <- persistenceSetupOf(events, snapshot).pure[F]
        events             <- PersistentActorEffect.of[F](actorRefOf, persistenceSetupOf).use { _ => events.get }
        snapshot           <- snapshot.get
      } yield {
        (snapshot, events)
      }
    }

    val timeout = 10.seconds

    def withCtx[A: ClassTag](f: ActorCtx[F, Any, Any] => F[A]): F[A] = {
      for {
        a <- actorRef.ask(Cmd.WithCtx(f), timeout)
        a <- a.cast[F, A]
      } yield a
    }

    for {
      terminated0 <- probe.watch(actorRef.toUnsafe)
      dispatcher  <- withCtx { _.dispatcher.pure[F] }
      _           <- Sync[F].delay { dispatcher.toString shouldEqual "Dispatcher[akka.actor.default-dispatcher]" }
      a           <- withCtx { _.actorRefOf(TestActors.blackholeProps, "child".some).allocated }
      (child0, childRelease) = a
      terminated1 <- probe.watch(child0)
      children    <- withCtx { _.children }
      _           <- Sync[F].delay { children.toList shouldEqual List(child0) }
      child        = withCtx { _.child("child") }
      child1      <- child
      _           <- Sync[F].delay { child1 shouldEqual child0.some }
      _           <- childRelease
      _           <- terminated1
      child1      <- child
      _           <- Sync[F].delay { child1 shouldEqual none[ActorRef] }
      children    <- withCtx { _.children }
      _           <- Sync[F].delay { children.toList shouldEqual List.empty }
      identity    <- actorRef.ask(Identify("id"), timeout)
      identity    <- identity.cast[F, ActorIdentity]
      _           <- withCtx { _.setReceiveTimeout(1.millis) }
      _           <- receiveTimeout
      _           <- Sync[F].delay { identity shouldEqual ActorIdentity("id", actorRef.toUnsafe.some) }
      seqNr       <- actorRef.ask(Cmd.Inc, timeout)
      _           <- Sync[F].delay { seqNr shouldEqual 2 }
      a           <- actorRef.ask(Cmd.Stop, timeout)
      _           <- Sync[F].delay { a shouldEqual "stopping" }
      _           <- terminated0
      ab          <- snapshotAndEvents
      (s, events) = ab
      _           <- Sync[F].delay { events shouldEqual List("inc") }
      _           <- Sync[F].delay { s shouldEqual Some(1) }
    } yield {}
  }
}

object PersistentActorOfSpec {

  type State = Int
  type Event = String

  sealed trait Cmd

  object Cmd {
    case object Inc extends Cmd
    case object Stop extends Cmd
    final case class WithCtx[F[_], A](f: ActorCtx[F, Any, Any] => F[A]) extends Cmd
  }


  implicit class AnyOps[A](val self: A) extends AnyVal {

    // TODO move out
    def cast[F[_] : Sync, B <: A](implicit tag: ClassTag[B]): F[B] = {
      def error = new ClassCastException(s"${ self.getClass.getName } cannot be cast to ${ tag.runtimeClass.getName }")

      tag.unapply(self) match {
        case Some(a) => a.pure[F]
        case None    => error.raiseError[F, B]
      }
    }
  }


  def receive[F[_] : Sync](
    ctx: ActorCtx[F, Any, Any],
    state: State,
    receiveTimeout: F[Unit],
    journaller: Journaller[F, Event],
    snapshotter: Snapshotter[F, State]
  ): F[Receive[F, Any, Any]] = {

    for {
      stateRef <- Ref[F].of(state)
    } yield {
      new Receive[F, Any, Any] {

        def apply(cmd: Any, reply: Reply[F, Any]) = {
          println(s"receive: $cmd")
          cmd match {
            case a: Cmd.WithCtx[_, _] =>
              val f = a.asInstanceOf[Cmd.WithCtx[F, Any]].f
              for {
                a <- f(ctx)
                _ <- reply(a)
              } yield {
                false
              }

            case ReceiveTimeout =>
              for {
                _ <- ctx.setReceiveTimeout(Duration.Inf)
                _ <- receiveTimeout
              } yield false

            case Cmd.Inc =>
              for {
                _     <- journaller.append(Nel.of("inc"))
                _     <- stateRef.update { _ + 1 }
                state <- stateRef.get
                a     <- snapshotter.save(state)
                _     <- a
                seqNr <- journaller.append(Nel.of("inc"))
                _     <- stateRef.update { _ + 1 }
                _     <- reply(seqNr)
              } yield {
                false
              }

            case Cmd.Stop =>
              for {
                _ <- reply("stopping")
              } yield {
                true
              }

            case _ => Error(s"unexpected $cmd").raiseError[F, Boolean]
          }
        }
      }
    }
  }
  

  case class Error(msg: String) extends RuntimeException(msg) with NoStackTrace
}
