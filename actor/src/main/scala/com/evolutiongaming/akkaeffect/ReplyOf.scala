package com.evolutiongaming.akkaeffect

import akka.actor.ActorRef

trait ReplyOf[F[_], A] {

  def apply(sender: Option[ActorRef]): Reply[F, A]
}
