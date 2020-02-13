package com.evolutiongaming.akkaeffect


import akka.actor.ActorRef
import cats.effect.Sync
import cats.implicits._

trait ReplyOf[F[_], A] {

  def apply(sender: ActorRef): Reply[F, A]
}

object ReplyOf {

  def fromActorRef[F[_] : Sync](self: ActorRef): ReplyOf[F, Any] = {
    sender: ActorRef => Reply.fromActorRef(to = sender, from = self.some)
  }
}
