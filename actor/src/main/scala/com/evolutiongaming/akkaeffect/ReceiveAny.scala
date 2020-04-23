package com.evolutiongaming.akkaeffect

import akka.actor.ActorRef
import cats.effect.Sync
import cats.implicits._
import com.evolutiongaming.akkaeffect.Receive.Stop


trait ReceiveAny[F[_]] {

  /**
    * Called strictly sequentially, next message will be processed only after we've done with the previous one
    * This basically preserves the semantic of Actors
    */
  def apply(msg: Any, sender: ActorRef): F[Stop]
}

object ReceiveAny {

  def apply[F[_]](f: (Any, ActorRef) => F[Stop]): ReceiveAny[F] = {
    (msg, sender) => f(msg, sender)
  }

  def fromReceive[F[_]: Sync](
    receive: Receive[F, Any, Any],
    self: ActorRef
  ): ReceiveAny[F] = {
    (msg: Any, sender: ActorRef) => {
      val reply = Reply.fromActorRef(to = sender, from = self.some)
      receive(msg, reply, sender)
    }
  }
}