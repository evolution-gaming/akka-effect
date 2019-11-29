package com.evolutiongaming.akkaeffect

import akka.actor.{ActorIdentity, ActorRef, ActorSystem, Identify, PoisonPill, Props, ReceiveTimeout}
import akka.testkit.TestActors
import cats.arrow.FunctionK
import cats.effect.concurrent.{Deferred, Ref}
import cats.effect.{Async, Concurrent, IO, Sync, Timer}
import cats.implicits._
import com.evolutiongaming.akkaeffect.IOSuite._
import com.evolutiongaming.catshelper.{FromFuture, ToFuture}

import scala.concurrent.duration._
import scala.reflect.ClassTag
import scala.util.control.NoStackTrace
import org.scalatest.funsuite.AsyncFunSuite
import org.scalatest.matchers.should.Matchers

class ActorOfSpec extends AsyncFunSuite with ActorSuite with Matchers {
  import ActorOfSpec._

  test("ActorOf") {
    `actorOf`[IO](actorSystem).run()
  }

  test("stop during start") {
    `stop during start`[IO](actorSystem).run()
  }

  test("fail actor") {
    `fail actor`[IO](actorSystem).run()
  }

  test("postStop") {
    `postStop`[IO](actorSystem).run()
  }

  def `actorOf`[F[_] : Concurrent : ToFuture : FromFuture](
    actorSystem: ActorSystem
  ): F[Unit] = {

    def receiveOf(receiveTimeout: F[Unit]) = (ctx: ActorCtx[F, Any, Any]) => {

      val receive = new Receive[F, Any, Any] {

        def apply(a: Any, reply: Reply[F, Any]) = {
          a match {
            case a: WithCtx[_, _] =>
              val f = a.asInstanceOf[WithCtx[F, Any]].f
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

            case "stop" =>
              for {
                _ <- reply("stopping")
              } yield {
                true
              }

            case _      => false.pure[F]
          }
        }

        def postStop = ().pure[F]
      }

      receive.mapK(FunctionK.id, FunctionK.id).some.pure[F]
    }

    for {
      receiveTimeout <- Deferred[F, Unit]
      receive         = receiveOf(receiveTimeout.complete(()))
      actorRefOf      = ActorRefOf[F](actorSystem)
      actorEffect     = ActorEffect.of[F](actorRefOf, receive)
      probe           = Probe.of[F](actorSystem)
      resources       = (actorEffect, probe).tupled
      result         <- resources.use { case (actorRef, probe) => `actorOf`[F](actorRef, probe, receiveTimeout.get) }
    } yield result
  }

  def `actorOf`[F[_] : Async : ToFuture : FromFuture](
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
      a           <- withCtx { _.actorOf(TestActors.blackholeProps, "child".some).allocated }
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


  private def `stop during start`[F[_] : Async : ToFuture : FromFuture : Timer](
    actorSystem: ActorSystem
  ) = {
    val actorRefOf = ActorRefOf[F](actorSystem)
    val receive = (_: ActorCtx[F, Any, Any]) => none[Receive[F, Any, Any]].pure[F]
    def actor = ActorOf[F](receive)
    val props = Props(actor)

    actorRefOf(props).use { actorRef =>
      val path = actorRef.path
      val timeout = 1.minute
      for {
        actorSelection <- Sync[F].delay { actorSystem.actorSelection(path) }
        ask             = Ask.fromActorSelection[F](actorSelection)
        a              <- ask(Identify("messageId"), timeout)
      } yield {
        a shouldEqual ActorIdentity("messageId", none[ActorRef])
      }
    }
  }

  private def `fail actor`[F[_] : Concurrent : ToFuture : FromFuture](
    actorSystem: ActorSystem
  ) = {

    val actorRefOf = ActorRefOf[F](actorSystem)

    def receiveOf(started: F[Unit]) = (_: ActorCtx[F, Any, Any]) => {
      for {
        _ <- started
      } yield {
        val receive = new Receive[F, Any, Any] {

          def apply(a: Any, reply: Reply[F, Any]) = {
            a match {
              case "fail" =>
                for {
                  _ <- reply("ok")
                  a <- (new RuntimeException("test") with NoStackTrace).raiseError[F, Stop]
                } yield a

              case "ping" =>
                for {
                  _ <- reply("pong")
                } yield {
                  false
                }

              case _ => false.pure[F]
            }
          }

          def postStop = ().pure[F]
        }

        receive.some
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
          _       <- started.get
          a       <- ask("ping", timeout)
          _       <- Sync[F].delay { a shouldEqual "pong" }
        } yield {}
      }
    } yield {
      result
    }
  }

  private def `postStop`[F[_] : Concurrent : ToFuture : FromFuture](
    actorSystem: ActorSystem
  ) = {

    val actorRefOf = ActorRefOf[F](actorSystem)

    def receiveOf(stopped: F[Unit]) = (_: ActorCtx[F, Any, Any]) => {
      val receive = new Receive[F, Any, Any] {

        def apply(a: Any, reply: Reply[F, Any]) = false.pure[F]

        def postStop = stopped
      }

      receive.some.pure[F]
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
    } yield {
      result
    }
  }
}


object ActorOfSpec {

  implicit class AnyOps[A](val self: A) extends AnyVal {

    // TODO move out
    def cast[F[_] : Sync, B <: A](implicit tag: ClassTag[B]): F[B] = {
      def error = new ClassCastException(s"${self.getClass.getName} cannot be cast to ${tag.runtimeClass.getName}")
      tag.unapply(self) match {
        case Some(a) => a.pure[F]
        case None    => error.raiseError[F, B]
      }
    }
  }

  final case class WithCtx[F[_], A](f: ActorCtx[F, Any, Any] => F[A])
}
