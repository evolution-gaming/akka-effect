package com.evolutiongaming.akkaeffect

import akka.actor.{Actor, ActorContext}
import cats.effect._
import com.evolutiongaming.catshelper.{FromFuture, ToFuture}

import scala.util.control.NoStackTrace

object ActorOf {

  def apply[F[_] : Async : ToFuture : FromFuture](
    receiveOf: ReceiveOf[F, Any, Any]
  ): Actor = {

    apply { (context: ActorContext, inReceive: InReceive) =>
      val stateful = AsyncBehavior(receiveOf, context, inReceive)
      SyncBehavior(stateful, inReceive, context)
    }
  }


  private[akkaeffect] def apply(
    behaviorOf: (ActorContext, InReceive) => SyncBehavior
  ): Actor = {

    new Actor {

      val adapter = InReceive.Adapter(self)

      var behavior = SyncBehavior.empty

      override def preStart() = {
        super.preStart()
        behavior = behaviorOf(context, adapter.inReceive)
      }

      def receive: Receive = adapter.receive orElse receiveMsg

      override def postStop() = {
        behavior.postStop()
        super.postStop()
      }

      def receiveMsg: Receive = { case msg => behavior.receive(msg, sender()) }
    }
  }

  private case object Terminated extends RuntimeException with NoStackTrace
}