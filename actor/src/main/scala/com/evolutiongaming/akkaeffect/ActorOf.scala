package com.evolutiongaming.akkaeffect

import akka.actor.{Actor, ActorRef}
import cats.effect._
import cats.implicits._
import com.evolutiongaming.catshelper.{FromFuture, ToFuture}

object ActorOf {

  def apply[F[_] : Sync : ToFuture : FromFuture](
    receiveOf: ReceiveOf[F, Any, Any]
  ): Actor = {

    type State = Receive[F, Any, Any]

    def onPreStart(self: ActorRef, ctx: ActorCtx[F, Any, Any]) = {
      receiveOf(ctx)
        .adaptErr { case error =>
          ActorError(s"$self.preStart failed to allocate receive with $error", error)
        }
    }

    def onReceive(a: Any, self: ActorRef, sender: ActorRef) = {
      val reply = Reply.fromActorRef[F](to = sender, from = self.some)
      state: State =>
        state(a, reply)
          .adaptError { case error =>
            ActorError(s"$self.receive failed on $a from $sender with $error", error)
          }
    }

    new Actor {

      val act = Act.adapter(self)

      val actorVar = ActorVar[F, State](act.value, context)

      override def preStart(): Unit = {
        super.preStart()
        act.sync {
          val ctx = ActorCtx[F](act.value, context)
          actorVar.preStart {
            onPreStart(self, ctx)
          }
        }
      }

      def receive: Receive = act.receive {
        case a => actorVar.receive { onReceive(a, self = self, sender = sender()) }
      }

      override def postStop(): Unit = {
        act.sync {
          actorVar.postStop()
        }
        super.postStop()
      }
    }
  }
}