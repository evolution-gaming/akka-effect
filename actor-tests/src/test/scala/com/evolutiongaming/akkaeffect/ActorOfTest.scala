package com.evolutiongaming.akkaeffect

import akka.actor.{ActorIdentity, ActorRef, ActorSystem, Identify, PoisonPill, Props}
import akka.testkit.TestActors
import cats.effect.implicits.effectResourceOps
import cats.effect.kernel.{Deferred, Ref}
import cats.effect.unsafe.implicits.global
import cats.effect.{Async, IO, Resource, Spawn, Sync, Temporal}
import cats.syntax.all._
import com.evolutiongaming.akkaeffect.IOSuite._
import com.evolutiongaming.akkaeffect.testkit.Probe
import com.evolutiongaming.catshelper.CatsHelper._
import com.evolutiongaming.catshelper.{FromFuture, ToFuture}
import org.scalatest.funsuite.AsyncFunSuite
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._
import scala.reflect.ClassTag
import scala.util.control.NoStackTrace

class ActorOfTest extends AsyncFunSuite with ActorSuite with Matchers {
  import ActorOfTest._

  for {
    async <- List(true)
  } yield {

    val prefix = if (async) "async" else "sync"
    val shift = if (async) Temporal[IO].sleep(1.millis) else ().pure[IO]

    test(s"$prefix all") {
      all[IO](actorSystem, shift).run()
    }

    test(s"$prefix receive") {
      receive[IO](actorSystem, shift).run()
    }

    test(s"$prefix stop during start") {
      `stop during start`[IO](actorSystem, shift).run()
    }

    test(s"$prefix fail actor") {
      `fail actor`[IO](actorSystem, shift).run()
    }

    test(s"$prefix fail during start") {
      `fail during start`[IO](actorSystem, shift).run()
    }

    test(s"$prefix stop") {
      stop[IO](actorSystem, shift).run()
    }

    test(s"$prefix ctx.stop") {
      `ctx.stop`[IO](actorSystem, shift).run()
    }

    test(s"$prefix stop externally") {
      `stop externally`[IO](actorSystem, shift).run()
    }

    test(s"$prefix setReceiveTimeout") {
      setReceiveTimeout[IO](actorSystem, shift).run()
    }

    test(s"$prefix watch & unwatch") {
      `watch & unwatch`[IO](actorSystem, shift).run()
    }
  }

  private def all[F[_]: Async: ToFuture: FromFuture](
    actorSystem: ActorSystem,
    shift: F[Unit]
  ): F[Unit] = {

    def all(
      actorRef: ActorEffect[F, Any, Any],
      probe: Probe[F],
      receiveTimeout: F[Unit]
    ): F[Unit] = {

      val timeout = 3.seconds

      def withCtx[A : ClassTag](f: ActorCtx[F] => F[A]): F[A] = {
        for {
          a <- actorRef.ask(WithCtx(f), timeout)
          a <- a
          a <- a.castM[F, A]
        } yield a
      }

      for {
        terminated0 <- probe.watch(actorRef.toUnsafe)
        dispatcher  <- withCtx { _.executor.pure[F] }
        _            = dispatcher.toString shouldEqual "Dispatcher[akka.actor.default-dispatcher]"
        ab          <- withCtx { ctx =>
          ActorRefOf
            .fromActorRefFactory[F](ctx.actorRefFactory)
            .apply(TestActors.blackholeProps, "child".some)
            .allocated
        }
        (child0, childRelease) = ab
        terminated1 <- probe.watch(child0)
        children    <- withCtx { _.children }
        _            = children shouldEqual List(child0)
        child        = withCtx { _.child("child") }
        child1      <- child
        _            = child1 shouldEqual child0.some
        _           <- childRelease
        _           <- terminated1
        child1      <- child
        _            = child1 shouldEqual none[ActorRef]
        children    <- withCtx { _.children }
        _            = children shouldEqual List.empty
        identity    <- actorRef.ask(Identify("id"), timeout).flatten
        identity    <- identity.castM[F, ActorIdentity]
        _           <- withCtx { _.setReceiveTimeout(1.millis) }
        _           <- receiveTimeout
        _            = identity shouldEqual ActorIdentity("id", actorRef.toUnsafe.some)
        a           <- actorRef.ask("stop", timeout).flatten
        _            = a shouldEqual "stopping"
        _           <- terminated0
      } yield {}
    }

    def receiveOf(receiveTimeout: F[Unit]): ReceiveOf[F, Call[F, Any, Any], Boolean] = {
      (actorCtx: ActorCtx[F]) => {

        val receive = Receive[Call[F, Any, Any]] { call =>
          call.msg match {
            case a: WithCtx[_, _] =>
              val f = a.asInstanceOf[WithCtx[F, Any]].f
              for {
                _ <- shift
                a <- f(actorCtx)
                _ <- call.reply(a)
              } yield false

            case "stop" =>
              for {
                _ <- shift
                _ <- call.reply("stopping")
              } yield true

            case _ => shift as false
          }
        } {
          for {
            _ <- shift
            _ <- actorCtx.setReceiveTimeout(Duration.Inf)
            _ <- receiveTimeout
          } yield false
        }

        Resource.make { shift as receive } { _ => shift }
      }
    }

    for {
      receiveTimeout <- Deferred[F, Unit]
      receive         = receiveOf(receiveTimeout.complete(()).void)
      actorRefOf      = ActorRefOf.fromActorRefFactory[F](actorSystem)
      actorEffect     = ActorEffect.of[F](actorRefOf, receive)
      probe           = Probe.of[F](actorRefOf)
      resources       = (actorEffect, probe).tupled
      result         <- resources.use { case (actorRef, probe) => all(actorRef, probe, receiveTimeout.get) }
    } yield result
  }


  private def receive[F[_]: Async: ToFuture: FromFuture](
    actorSystem: ActorSystem,
    shift: F[Unit]
  ): F[Unit] = {

    case class GetAndInc(delay: F[Unit])

    def receiveOf = ReceiveOf.const {
      val receive = for {
        state <- Ref[F].of(0)
      } yield {
        Receive[Call[F, Any, Any]] { call =>
          call.msg match {
            case a: GetAndInc =>
              for {
                _ <- shift
                _ <- a.delay
                a <- state.modify { a => (a + 1, a) }
                _ <- call.reply(a)
              } yield false

            case _ => shift as false
          }
        } {
          false.pure[F]
        }
      }
      Resource.make { shift productR receive } { _ => shift }
    }

    val actorRefOf = ActorRefOf.fromActorRefFactory[F](actorSystem)

    ActorEffect
      .of[F](actorRefOf, receiveOf)
      .use { actorRef =>
        val timeout = 3.seconds

        def getAndInc(delay: F[Unit]) = {
          actorRef.ask(GetAndInc(delay), timeout)
        }

        for {
          a   <- getAndInc(().pure[F]).flatten
          _    = a shouldEqual 0
          d0  <- Deferred[F, Unit]
          a0  <- getAndInc(d0.get)
          ref <- Ref[F].of(false)
          a1  <- getAndInc(ref.set(true))
          d1  <- Deferred[F, Unit]
          a2  <- getAndInc(d1.get)
          a3  <- getAndInc(().pure[F])
          b   <- ref.get
          _    = b shouldEqual false
          _   <- d0.complete(())
          a   <- a0
          _    = a shouldEqual 1
          a   <- a1
          _    = a shouldEqual 2
          b   <- ref.get
          _    = b shouldEqual true
          _   <- d1.complete(())
          a   <- a2
          _    = a shouldEqual 3
          a   <- a3
          _    = a shouldEqual 4
        } yield {}
      }
  }


  private def `stop during start`[F[_]: Async: ToFuture: FromFuture](
    actorSystem: ActorSystem,
    shift: F[Unit]
  ) = {
    val actorRefOf = ActorRefOf.fromActorRefFactory[F](actorSystem)
    val receiveOf = ReceiveOf[F] { actorCtx =>
      Resource.make {
        for {
          _ <- shift
          _ <- actorCtx.stop
        } yield {
          Receive.const[Envelope[Any]](false.pure[F])
        }
      } {
        _ => shift
      }
    }
    def actor = ActorOf[F](receiveOf)
    val props = Props(actor)
    val probe = Probe.of[F](actorRefOf)
    val actorRef = actorRefOf(props)
    (probe, actorRef)
      .tupled
      .use { case (probe, actorRef) =>
        probe.watch(actorRef).flatten
      }
  }


  private def `fail actor`[F[_]: Async: ToFuture: FromFuture](
    actorSystem: ActorSystem,
    shift: F[Unit]
  ) = {

    val actorRefOf = ActorRefOf.fromActorRefFactory[F](actorSystem)

    def receiveOf(started: F[Unit]) = ReceiveOf.const {
      val receive = Receive[Call[F, Any, Any]] { call =>
        call.msg match {
          case "fail" =>
            for {
              _ <- shift
              _ <- call.reply("ok")
              a <- error.raiseError[F, Boolean]
            } yield a

          case "ping" =>
            for {
              _ <- shift
              _ <- call.reply("pong")
            } yield false
          case _      =>
            shift as false
        }
      } {
        false.pure[F]
      }

      for {
        _ <- started.toResource
        a <- Resource.make { shift as receive } { _ => shift }
      } yield a
    }

    for {
      started   <- Deferred[F, Unit]
      ref       <- Ref[F].of(started)
      receiveOf <- receiveOf(ref.get.flatMap(_.complete(()).void)).pure[F]
      actor      = () => ActorOf[F](receiveOf.toReceiveOfEnvelope)
      props      = Props(actor())
      result    <- actorRefOf(props).use { actorRef =>
        val ask = Ask.fromActorRef[F](actorRef)
        val timeout = 1.minute
        for {
          a       <- ask("ping", timeout).flatten
          _        = a shouldEqual "pong"
          _       <- started.get
          started <- Deferred[F, Unit]
          _       <- ref.set(started)
          a       <- ask("fail", timeout).flatten
          _        = a shouldEqual "ok"
        } yield {}
      }
    } yield result
  }


  private def `fail during start`[F[_]: Async: ToFuture: FromFuture](
    actorSystem: ActorSystem,
    shift: F[Unit]
  ) = {
    val actorRefOf = ActorRefOf.fromActorRefFactory[F](actorSystem)

    val actor = () => ActorOf[F] { _ => (shift *> error.raiseError[F, Receive[F, Envelope[Any], Boolean]]).toResource }
    val props = Props(actor())

    val result = for {
      actorRef <- actorRefOf(props)
      probe    <- Probe.of[F](actorRefOf)
      result   <- probe.watch(actorRef).flatten.toResource
    } yield result

    result.use { _.pure[F] }
  }


  private def stop[F[_]: Async: ToFuture: FromFuture](
    actorSystem: ActorSystem,
    shift: F[Unit]
  ) = {

    val actorRefOf = ActorRefOf.fromActorRefFactory[F](actorSystem)

    def receiveOf(stopped: F[Unit]): ReceiveOf[F, Call[F, Any, Any], Boolean] = {
      (_: ActorCtx[F]) => {
        Resource
          .make {
            val receive = Receive[Call[F, Any, Any]] { call =>
              call.msg match {
                case "stop" => for {
                  _ <- shift
                  _ <- call.reply(())
                } yield true
                case _      =>
                  shift as false
              }
            } {
              false.pure[F]
            }
            shift as receive
          } { _ =>
            shift *> stopped
          }
      }
    }

    for {
      stopped <- Deferred[F, Unit]
      receive  = receiveOf(stopped.complete(()).void)
      actor    = () => ActorOf[F](receive.toReceiveOfEnvelope)
      props    = Props(actor())
      result  <- actorRefOf(props).use { actorRef =>
        val ask = Ask.fromActorRef[F](actorRef)
        for {
          _ <- ask("stop", 1.second, none)
          _ <- stopped.get
        } yield {}
      }
    } yield result
  }


  private def `ctx.stop`[F[_]: Async: ToFuture: FromFuture](
    actorSystem: ActorSystem,
    shift: F[Unit]
  ) = {

    val actorRefOf = ActorRefOf.fromActorRefFactory[F](actorSystem)

    def receiveOf(stopped: F[Unit]): ReceiveOf[F, Call[F, Any, Any], Boolean] = {
      actorCtx => {
        Resource.make {
          shift.as {
            Receive[Call[F, Any, Any]] { call =>
              call.msg match {
                case "stop" => for {
                  _ <- shift
                  _ <- actorCtx.stop
                } yield false
                case _      =>
                  shift as false
              }
            } {
              false.pure[F]
            }
          }
        } { _ =>
          shift *> stopped
        }
      }
    }

    for {
      stopped <- Deferred[F, Unit]
      receive  = receiveOf(stopped.complete(()).void)
      actor    = () => ActorOf[F](receive.toReceiveOfEnvelope)
      props    = Props(actor())
      result  <- actorRefOf(props).use { actorRef =>
        val ask = Ask.fromActorRef[F](actorRef)
        for {
          _ <- ask("stop", 1.second, none)
          _ <- stopped.get
        } yield {}
      }
    } yield result
  }


  private def `stop externally`[F[_]: Async: ToFuture](
    actorSystem: ActorSystem,
    shift: F[Unit]
  ) = {

    val actorRefOf = ActorRefOf.fromActorRefFactory[F](actorSystem)

    def receiveOf(stopped: F[Unit]): ReceiveOf[F, Call[F, Any, Any], Boolean] =
      ReceiveOf.const {
          Resource
          .make {
            shift as Receive.const[Call[F, Any, Any]](false.pure[F])
          } { _ =>
            shift *> stopped
          }
      }

    for {
      stopped <- Deferred[F, Unit]
      receive  = receiveOf(stopped.complete(()).void)
      actor    = () => ActorOf[F](receive.toReceiveOfEnvelope)
      props    = Props(actor())
      result  <- actorRefOf(props).use { actorRef =>
        val tell = Tell.fromActorRef[F](actorRef)
        for {
          _ <- tell(PoisonPill)
          _ <- stopped.get
        } yield {}
      }
    } yield result
  }


  private def setReceiveTimeout[F[_]: Async: ToFuture: FromFuture](
    actorSystem: ActorSystem,
    shift: F[Unit]
  ) = {

    val actorRefOf = ActorRefOf.fromActorRefFactory[F](actorSystem)

    def receiveOf(
      timedOut: Deferred[F, Unit],
    ): ReceiveOf[F, Any, Boolean] = {
      actorCtx => {
        val receive = for {
          _ <- shift
          _ <- actorCtx.setReceiveTimeout(10.millis)
        } yield {
          Receive[Any] { _ =>
            shift.as(false)
          } {
            for {
              _ <- shift
              _ <- timedOut.complete(())
            } yield true
          }
        }
        receive.toResource
      }
    }

    for {
      timedOut  <- Deferred[F, Unit]
      receiveOf <- receiveOf(timedOut).pure[F]
      result     = ActorEffect.of(actorRefOf, receiveOf)
      result    <- result.use { _ => timedOut.get}
    } yield result
  }


  private def `watch & unwatch`[F[_]: Async: ToFuture: FromFuture](
    actorSystem: ActorSystem,
    shift: F[Unit]
  ) = {

    sealed trait Msg

    object Msg {
      final case class Watch(actorRef: ActorRef) extends Msg
      final case class Unwatch(actorRef: ActorRef) extends Msg
      final case class Terminated(actorRef: ActorRef) extends Msg
    }

    val actorRefOf = ActorRefOf.fromActorRefFactory[F](actorSystem)

    def receiveOf(
      terminated: Deferred[F, ActorRef],
      stopped: F[Unit]
    ): ReceiveOf[F, Call[F, Msg, Unit], Boolean] = {
      actorCtx => {
        val receive = Receive[Call[F, Msg, Unit]] { call =>
          for {
            _    <- shift
            stop <- call.msg match {
              case Msg.Watch(actorRef)      =>
                actorCtx
                  .watch(actorRef, Msg.Terminated(actorRef))
                  .as(false)
              case Msg.Unwatch(actorRef)    =>
                actorCtx
                  .unwatch(actorRef)
                  .as(false)
              case Msg.Terminated(actorRef) =>
                terminated
                  .complete(actorRef)
                  .as(true)
            }
            _    <- call.reply(())
          } yield stop
        } {
          false.pure[F]
        }

        Resource.make { shift as receive } { _ => shift *> stopped }
      }
    }

    val timeout = 3.seconds

    for {
      stopped    <- Deferred[F, Unit]
      terminated <- Deferred[F, ActorRef]
      receiveOf  <- receiveOf(terminated, stopped.complete(()).void)
        .convert[Any, Any, Boolean](_.castM[F, Msg], (_: Any).pure[F], _.pure[F])
        .pure[F]
      result  = for {
        actorEffect <- ActorEffect.of(actorRefOf, receiveOf)
        actorRef0   <- actorRefOf(TestActors.blackholeProps)
        actorRef1   <- actorRefOf(TestActors.blackholeProps)
        result      <- Resource.eval {
          for {
            _ <- actorEffect.ask(Msg.Watch(actorRef0), timeout)
            _ <- actorEffect.ask(Msg.Unwatch(actorRef0), timeout).flatten
            _ <- Sync[F].delay { actorSystem.stop(actorRef0) }
            _ <- actorEffect.ask(Msg.Watch(actorRef1), timeout).flatten
            _ <- Sync[F].delay { actorSystem.stop(actorRef1) }
            _ <- stopped.get
            a <- terminated.get
            _ = a shouldEqual actorRef1
          } yield {}
        }
      } yield result
      result     <- result.use { _.pure[F] }
    } yield result
  }
}


object ActorOfTest {

  val error: Throwable = new RuntimeException("test") with NoStackTrace

  final case class WithCtx[F[_], A](f: ActorCtx[F] => F[A])
}
