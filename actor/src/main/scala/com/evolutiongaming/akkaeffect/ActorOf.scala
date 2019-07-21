package com.evolutiongaming.akkaeffect

import akka.actor.{Actor, ActorContext, ActorRef}
import cats.effect._
import cats.implicits._
import com.evolutiongaming.catshelper.{FromFuture, ToFuture}

import scala.concurrent.Future
import scala.concurrent.duration.Duration

object ActorOf {

  def apply[F[_] : Async : ToFuture : FromFuture](
    receive: ActorCtx.Any[F] => F[Option[Receive.Any[F]]]
  ): Actor = {

    type S = Receive.Any[F]

    var state = Future.successful(none[S])

    final case class Run(f: () => Unit)

    def update(f: Option[S] => F[Option[S]]): Unit = {
      val fa = for {
        state <- FromFuture[F].apply { state }
        state <- f(state)
      } yield state
      state = ToFuture[F].apply { fa }
    }

    def stopSelf(context: ActorContext) = {
      val self = context.self
      Sync[F].delay { context.stop(self) }
    }

    def run(self: ActorRef)(f: => Unit): F[Unit] = {
      val run = Run(() => f)
      Sync[F].delay { self.tell(run, self) }
    }

    def get[A](self: ActorRef)(f: => A): F[A] = {
      Async[F].asyncF[A] { callback =>
        run(self) { callback(f.asRight) }
      }
    }

    def actorCtxOf(context: ActorContext): ActorCtx.Any[F] = {

      new ActorCtx.Any[F] {

        val self = ActorEffect.fromActor(context.self)

        def dispatcher = context.dispatcher

        def setReceiveTimeout(timeout: Duration) = {
          run(context.self) { context.setReceiveTimeout(timeout) }
        }

        def child(name: String) = {
          get(context.self) { context.child(name)  }
        }

        val children = get(context.self) { context.children }

        val actorOf = ActorRefOf[F](context)
      }
    }

    def onPreStart(context: ActorContext): Unit = {

      val ctx = actorCtxOf(context)

      update { _ =>
        for {
          state <- receive(ctx)
          _     <- state.fold(stopSelf(context)) { _ => ().pure[F] }
        } yield state
      }
    }

    def onAny(a: Any, sender: ActorRef, context: ActorContext): Unit = {

      val self = context.self

      def fail(error: Throwable) = run(self) { throw error } // TODO test

      update { receive =>
        receive.fold(receive.pure[F]) { receive =>
          val reply = Reply.fromActorRef[F](to = sender, from = Some(self))
          for {
            result <- receive(a, reply).attempt
            state  <- result match {
              case Right(false) => receive.some.pure[F]
              case Right(true)  => stopSelf(context).as(none[S])
              case Left(error)  => fail(error).as(none[S])
            }
          } yield state
        }
      }
    }

    def onPostStop(): Unit = {
      update { receive =>
        receive.foldMapM(_.postStop).as(none[S])
      }
    }

    new Actor {

      override def preStart(): Unit = {
        super.preStart()
        onPreStart(context) // TODO test immediate stop
      }

      def receive: Receive = {
        case Run(f) => f()
        case msg    => onAny(msg, sender(), context)
      }

      override def postStop(): Unit = {
        onPostStop()
        super.postStop()
      }
    }
  }
}