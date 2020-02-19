package com.evolutiongaming.akkaeffect

import akka.actor.{ActorIdentity, ActorRef, ActorSystem, Identify, PoisonPill, Props, ReceiveTimeout}
import akka.testkit.TestActors
import cats.effect.concurrent.{Deferred, Ref}
import cats.effect.{Concurrent, IO, Resource, Sync, Timer}
import cats.implicits._
import com.evolutiongaming.akkaeffect.IOSuite._
import com.evolutiongaming.akkaeffect.AkkaEffectHelper._
import com.evolutiongaming.catshelper.CatsHelper._
import com.evolutiongaming.catshelper.{FromFuture, ToFuture}
import org.scalatest.funsuite.AsyncFunSuite
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._
import scala.reflect.ClassTag
import scala.util.control.NoStackTrace

class ActorOfTest extends AsyncFunSuite with ActorSuite with Matchers {
  import ActorOfTest._


  // TODO refactor tests to support Ask F[F[_]]
  for {
    async <- List(false, true)
  } yield {

    val prefix = if (async) "async" else "sync"
    val delay = if (async) Timer[IO].sleep(1.millis) else ().pure[IO]

    test(s"$prefix all") {
      all[IO](actorSystem, delay).run()
    }

    test(s"$prefix receive") {
      receive[IO](actorSystem, delay).run()
    }

    test(s"$prefix stop during start") {
      `stop during start`[IO](actorSystem, delay).run()
    }

    test(s"$prefix fail actor") {
      `fail actor`[IO](actorSystem, delay).run()
    }

    test(s"$prefix fail during start") {
      `fail during start`[IO](actorSystem, delay).run()
    }

    test(s"$prefix stop") {
      stop[IO](actorSystem, delay).run()
    }

    test(s"$prefix stop externally") {
      `stop externally`[IO](actorSystem, delay).run()
    }
  }

  private def all[F[_] : Concurrent : ToFuture : FromFuture](
    actorSystem: ActorSystem,
    delay: F[Unit]
  ): F[Unit] = {

    def all(
      actorRef: ActorEffect[F, Any, Any],
      probe: Probe[F],
      receiveTimeout: F[Unit]
    ): F[Unit] = {

      val timeout = 1.second

      def withCtx[A : ClassTag](f: ActorCtx[F, Any, Any] => F[A]): F[A] = {
        for {
          a <- actorRef.ask(WithCtx(f), timeout)
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
        a           <- actorRef.ask("stop", timeout)
        _           <- Sync[F].delay { a shouldEqual "stopping" }
        _           <- terminated0
      } yield {}
    }

    def receiveOf(receiveTimeout: F[Unit]): ReceiveOf[F, Any, Any] = {
      (ctx: ActorCtx[F, Any, Any]) => {

        val receive: Receive[F, Any, Any] = {

          (a: Any, reply: Reply[F, Any]) => {
            a match {
              case a: WithCtx[_, _] =>
                val f = a.asInstanceOf[WithCtx[F, Any]].f
                for {
                  _ <- delay
                  a <- f(ctx)
                  _ <- reply(a)
                } yield false

              case ReceiveTimeout =>
                for {
                  _ <- delay
                  _ <- ctx.setReceiveTimeout(Duration.Inf)
                  _ <- receiveTimeout
                } yield false

              case "stop" =>
                for {
                  _ <- delay
                  _ <- reply("stopping")
                } yield true

              case _ => delay as false
            }
          }
        }

        Resource.make { delay as receive.some } { _ => delay }
      }
    }

    for {
      receiveTimeout <- Deferred[F, Unit]
      receive         = receiveOf(receiveTimeout.complete(()))
      actorRefOf      = ActorRefOf[F](actorSystem)
      actorEffect     = ActorEffect.of[F](actorRefOf, receive)
      probe           = Probe.of[F](actorRefOf)
      resources       = (actorEffect, probe).tupled
      result         <- resources.use { case (actorRef, probe) => all(actorRef, probe, receiveTimeout.get) }
    } yield result
  }


  private def receive[F[_] : Concurrent : ToFuture : FromFuture](
    actorSystem: ActorSystem,
    delay: F[Unit]
  ): F[Unit] = {

    case class GetAndInc(delay: F[Unit])

    def receiveOf: ReceiveOf[F, Any, Any] = {
      (_: ActorCtx[F, Any, Any]) => {

        val receive = for {
          state <- Ref[F].of(0)
        } yield {
          val receive: Receive[F, Any, Any] = {
            (a: Any, reply: Reply[F, Any]) => {
              a match {
                case a: GetAndInc =>
                  for {
                    _ <- delay
                    _ <- a.delay
                    a <- state.modify { a => (a + 1, a) }
                    _ <- reply(a)
                  } yield false

                case _ => delay as false
              }
            }
          }
          receive.some
        }
        Resource.make { delay productR receive } { _ => delay }
      }
    }

    val actorRefOf = ActorRefOf[F](actorSystem)

    ActorEffect
      .of[F](actorRefOf, receiveOf)
      .use { actorRef =>
        val timeout = 1.second

        def getAndInc(delay: F[Unit]) = {
          actorRef
            .ask(GetAndInc(delay), timeout)
            .startEnsure
            .map { _.join }
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


  private def `stop during start`[F[_] : Concurrent : ToFuture : FromFuture : Timer](
    actorSystem: ActorSystem,
    delay: F[Unit]
  ) = {
    val actorRefOf = ActorRefOf[F](actorSystem)

    val receiveOf: ReceiveOf[F, Any, Any] = _ => Resource.make { delay as none[Receive[F, Any, Any]] } { _ => delay }
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


  private def `fail actor`[F[_] : Concurrent : ToFuture : FromFuture](
    actorSystem: ActorSystem,
    delay: F[Unit]
  ) = {

    val actorRefOf = ActorRefOf[F](actorSystem)

    def receiveOf(started: F[Unit]): ReceiveOf[F, Any, Any] = {
      (_: ActorCtx[F, Any, Any]) => {

        val receive: Receive[F, Any, Any] = {
          (a: Any, reply: Reply[F, Any]) => {
            a match {
              case "fail" =>
                for {
                  _ <- delay
                  _ <- reply("ok")
                  a <- error.raiseError[F, Receive.Stop]
                } yield a

              case "ping" =>
                for {
                  _ <- delay
                  _ <- reply("pong")
                } yield false
              case _      =>
                delay as false
            }
          }
        }

        for {
          _ <- Resource.liftF(started)
          a <- Resource.make { delay as receive } { _ => delay }
        } yield {
          a.some
        }
      }
    }

    for {
      started <- Deferred[F, Unit]
      ref     <- Ref[F].of(started)
      receive  = receiveOf(ref.get.flatMap(_.complete(())))
      actor    = () => ActorOf[F](receive)
      props    = Props(actor())
      result  <- actorRefOf(props).use { actorRef =>
        val ask = Ask.fromActorRef[F](actorRef)
        val timeout = 1.minute
        for {
          a       <- ask("ping", timeout)
          _       <- Sync[F].delay { a shouldEqual "pong" }
          _       <- started.get
          started <- Deferred[F, Unit]
          _       <- ref.set(started)
          a       <- ask("fail", timeout)
          _       <- Sync[F].delay { a shouldEqual "ok" }
        } yield {}
      }
    } yield result
  }


  private def `fail during start`[F[_] : Concurrent : ToFuture : FromFuture](
    actorSystem: ActorSystem,
    delay: F[Unit]
  ) = {
    val actorRefOf = ActorRefOf[F](actorSystem)

    val actor = () => ActorOf[F] { _ => Resource.liftF(delay *> error.raiseError[F, Option[Receive[F, Any, Any]]]) }
    val props = Props(actor())

    val result = for {
      actorRef <- actorRefOf(props)
      probe    <- Probe.of[F](actorRefOf)
      result   <- Resource.liftF { probe.watch(actorRef).flatten }
    } yield result

    result.use { _.pure[F] }
  }


  private def stop[F[_] : Concurrent : ToFuture : FromFuture](
    actorSystem: ActorSystem,
    delay: F[Unit]
  ) = {

    val actorRefOf = ActorRefOf[F](actorSystem)

    def receiveOf(stopped: F[Unit]): ReceiveOf[F, Any, Any] = {
      (_: ActorCtx[F, Any, Any]) => {
        Resource
          .make {
            val receive: Receive[F, Any, Any] = {
              (a: Any, reply: Reply[F, Any]) =>
                a match {
                  case "stop" => for {
                    _ <- delay
                    _ <- reply(())
                  } yield true
                  case _      =>
                    delay as false
                }
            }
            delay as receive.some
          } { _ =>
            delay *> stopped
          }
      }
    }

    for {
      stopped <- Deferred[F, Unit]
      receive  = receiveOf(stopped.complete(()))
      actor    = () => ActorOf[F](receive)
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


  private def `stop externally`[F[_] : Concurrent : ToFuture : FromFuture](
    actorSystem: ActorSystem,
    delay: F[Unit]
  ) = {

    val actorRefOf = ActorRefOf[F](actorSystem)

    def receiveOf(stopped: F[Unit]): ReceiveOf[F, Any, Any] = {
      (_: ActorCtx[F, Any, Any]) => {
        Resource
          .make {
            delay as Receive.empty[F, Any, Any].some
          } { _ =>
            delay *> stopped
          }
      }
    }

    for {
      stopped <- Deferred[F, Unit]
      receive  = receiveOf(stopped.complete(()))
      actor    = () => ActorOf[F](receive)
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
}


object ActorOfTest {

  val error: Throwable = new RuntimeException("test") with NoStackTrace

  final case class WithCtx[F[_], A](f: ActorCtx[F, Any, Any] => F[A])
}
