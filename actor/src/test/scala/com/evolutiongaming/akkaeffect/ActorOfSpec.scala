package com.evolutiongaming.akkaeffect

import akka.actor.{ActorIdentity, ActorRef, ActorSystem, Identify, PoisonPill, Props, ReceiveTimeout}
import akka.testkit.TestActors
import cats.effect.concurrent.{Deferred, Ref}
import cats.effect.{Async, Concurrent, IO, Resource, Sync, Timer}
import cats.implicits._
import com.evolutiongaming.akkaeffect.IOSuite._
import com.evolutiongaming.catshelper.{FromFuture, ToFuture}
import org.scalatest.funsuite.AsyncFunSuite
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._
import scala.reflect.ClassTag
import scala.util.control.NoStackTrace

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

  test("fail during start") {
    `fail during start`[IO](actorSystem).run()
  }

  test("postStop") {
    `postStop`[IO](actorSystem).run()
  }

  def `actorOf`[F[_] : Concurrent : ToFuture : FromFuture](
    actorSystem: ActorSystem
  ): F[Unit] = {

    def receiveOf(receiveTimeout: F[Unit]): ReceiveOf[F, Any, Any] = (ctx: ActorCtx[F, Any, Any]) => {

      val receive: Receive[F, Any, Any] = {

        (a: Any, reply: Reply[F, Any]) =>{
          a match {
            case a: WithCtx[_, _] =>
              val f = a.asInstanceOf[WithCtx[F, Any]].f
              for {
                a <- f(ctx)
                _ <- reply(a)
              } yield false

            case ReceiveTimeout =>
              for {
                _ <- ctx.setReceiveTimeout(Duration.Inf)
                _ <- receiveTimeout
              } yield false

            case "stop" => reply("stopping").as(true)

            case _      => false.pure[F]
          }
        }
      }

      Resource.liftF(receive.some.pure[F])
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


  private def `stop during start`[F[_] : Async : ToFuture : FromFuture : Timer](
    actorSystem: ActorSystem
  ) = {
    val actorRefOf = ActorRefOf[F](actorSystem)

    val receive = (_: ActorCtx[F, Any, Any]) => Resource.liftF(none[Receive[F, Any, Any]].pure[F])// TODO
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

    def receiveOf(started: F[Unit]): ReceiveOf[F, Any, Any] = {
      (_: ActorCtx[F, Any, Any]) => {
        Resource
          .liftF(started)
          .as {
            val receive: Receive[F, Any, Any] = {
              (a: Any, reply: Reply[F, Any]) => {
                a match {
                  case "fail" =>
                    for {
                      _ <- reply("ok")
                      a <- error.raiseError[F, Receive.Stop]
                    } yield a

                  case "ping" => reply("pong").as(false)
                  case _      => false.pure[F]
                }
              }
            }
            receive.some
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
    actorSystem: ActorSystem
  ) = {
    val actorRefOf = ActorRefOf[F](actorSystem)

    val actor = () => ActorOf[F] { _ => Resource.liftF(error.raiseError[F, Option[Receive[F, Any, Any]]]) }
    val props = Props(actor())

    val result = for {
      actorRef <- actorRefOf(props)
      probe    <- Probe.of[F](actorSystem)
      result   <- Resource.liftF { probe.watch(actorRef).flatten }
    } yield result

    result.use { _.pure[F] }
  }

  private def `postStop`[F[_] : Concurrent : ToFuture : FromFuture](
    actorSystem: ActorSystem
  ) = {

    val actorRefOf = ActorRefOf[F](actorSystem)

    def receiveOf(stopped: F[Unit]): ReceiveOf[F, Any, Any] = {
      (_: ActorCtx[F, Any, Any]) => {
        Resource
          .make {
            val receive: Receive[F, Any, Any] = (_: Any, _: Reply[F, Any]) => false.pure[F]
            receive.some.pure[F]
          } { _ =>
            stopped
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
    } yield {
      result
    }
  }
}


object ActorOfSpec {

  val error: Throwable = new RuntimeException("test") with NoStackTrace

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
