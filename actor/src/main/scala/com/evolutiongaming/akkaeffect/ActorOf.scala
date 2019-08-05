package com.evolutiongaming.akkaeffect

import akka.actor.{Actor, ActorRef}
import cats.effect._
import cats.implicits._
import com.evolutiongaming.catshelper.{FromFuture, ToFuture}

import scala.concurrent.Future

object ActorOf {

  def apply[F[_] : Async : ToFuture : FromFuture](
    receive: ActorCtx[F, Any, Any] => F[Option[Receive[F, Any, Any]]]
  ): Actor = {

    type S = Receive[F, Any, Any]

    var state = Future.successful(none[S])

    def update(f: Option[S] => F[Option[S]]): Unit = {
      val fa = for {
        state <- FromFuture[F].apply { state }
        state <- f(state)
      } yield state
      state = ToFuture[F].apply { fa }
    }

    def onPreStart(adapter: ActorContextAdapter[F]): Unit = {
      update {
        case Some(_) => ActorError("onPreStart failed: unexpected state").raiseError[F, Option[S]]
        case None    =>
          for {
            state <- receive(adapter.ctx)
            _     <- state.fold(adapter.stop) { _ => ().pure[F] }
          } yield state
      }
    }

    def onAny(a: Any, adapter: ActorContextAdapter[F], self: ActorRef, sender: ActorRef): Unit = {

      def fail(error: Throwable) = adapter.run { throw error }

      update { receive =>
        receive.fold(receive.pure[F]) { receive =>
          val reply = Reply.fromActorRef[F](to = sender, from = Some(self))
          for {
            result <- receive(a, reply).attempt
            state  <- result match {
              case Right(false) => receive.some.pure[F]
              case Right(true)  => adapter.stop.as(none[S])
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

      val adapter = ActorContextAdapter(context)

      override def preStart(): Unit = {
        super.preStart()
        onPreStart(adapter)
      }

      def receive: Receive = adapter.receive orElse {
        case msg => onAny(msg, adapter, self = self, sender = sender())
      }

      override def postStop(): Unit = {
        onPostStop()
        super.postStop()
      }
    }
  }
}