package com.evolutiongaming.akkaeffect

import akka.actor.Actor

final case class Adapter[A](value: A, receive: Actor.Receive)