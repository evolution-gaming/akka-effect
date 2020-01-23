package com.evolutiongaming.akkaeffect

import akka.actor.{Actor, ActorRef}
import cats.effect._
import cats.implicits._
import com.evolutiongaming.catshelper.{FromFuture, ToFuture}

object ActorOf {

  def apply[F[_] : Async : ToFuture : FromFuture](
    receive: ActorCtx[F, Any, Any] => F[Option[Receive[F, Any, Any]]]
  ): Actor = {

    type Rcv = Receive[F, Any, Any]

    val state = StateVar[F].of(none[Rcv])

    def onPreStart(adapter: ActorContextAdapter[F], self: ActorRef): Unit = {
      state {
        case Some(_) => ActorError(s"$self.onPreStart failed: unexpected state").raiseError[F, Option[Rcv]]
        case None    =>
          for {
            state <- receive(adapter.ctx)
            _     <- state.fold { adapter.stop } { _ => ().pure[F] }
          } yield state
      }
    }

    def onAny(a: Any, adapter: ActorContextAdapter[F], self: ActorRef, sender: ActorRef): Unit = {
      state {
        case Some(receive) =>
          val reply = Reply.fromActorRef[F](to = sender, from = Some(self))
          for {
            result <- receive(a, reply).attempt
            state  <- result match {
              case Right(false) => receive.some.pure[F]
              case Right(true)  => adapter.stop.as(none[Rcv])
              case Left(error)  => adapter.fail(error).as(none[Rcv])
            }
          } yield state
        case receive       => receive.pure[F]
      }
    }

    def onPostStop(): Unit = {
      state { receive =>
        receive
          .foldMapM { _.postStop }
          .as(none[Rcv])
      }
    }

    new Actor {

      val adapter = ActorContextAdapter(context)

      override def preStart(): Unit = {
        super.preStart()
        onPreStart(adapter, self)
      }

      def receive: Receive = adapter.receive orElse {
        case a => onAny(a, adapter, self = self, sender = sender())
      }

      override def postStop(): Unit = {
        onPostStop()
        super.postStop()
      }
    }
  }
}