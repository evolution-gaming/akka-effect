package com.evolutiongaming.akkaeffect

import akka.actor.{Actor, ActorContext, ActorRef, Props}
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

      val self = context.self

      new ActorCtx.Any[F] {

        val tell = Tell.fromActorRef(self)

        val ask = Ask.fromActorRef(self)

        def dispatcher = context.dispatcher

        def setReceiveTimeout(timeout: Duration) = {
          run(self) { context.setReceiveTimeout(timeout) }
        }

        def child(name: String) = {
          get(self) { context.child(name)  }
        }

        val children = {
          get(self) { context.children }
        }

        def actorOf(props: Props, name: Option[String] = None) = {
          Resource.make {
            Sync[F].delay {
              name.fold {
                context.actorOf(props)
              } { name =>
                context.actorOf(props, name)
              }
            }
          } { actorRef =>
            Sync[F].delay { context.stop(actorRef) }
          }
        }
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

    def onMsg(msg: Any, sender: ActorRef, context: ActorContext): Unit = {

      val self = context.self

      def fail(error: Throwable) = run(self) { throw error }

      update { receive =>
        receive.fold(receive.pure[F]) { receive =>
          val reply = Reply[F](to = sender, from = Some(self))
          for {
            result <- receive(msg, reply).attempt
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
        onPreStart(context)
      }

      def receive: Receive = {
        case Run(f) => f()
        case msg    => onMsg(msg, sender(), context)
      }

      override def postStop(): Unit = {
        onPostStop()
        super.postStop()
      }
    }
  }
}