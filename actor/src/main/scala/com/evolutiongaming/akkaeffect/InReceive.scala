package com.evolutiongaming.akkaeffect

import akka.actor.{Actor, ActorRef}

private[akkaeffect] trait InReceive {

  def apply[A](f: => A): Unit
}


private[akkaeffect] object InReceive {

  trait Adapter {

    def inReceive: InReceive

    def receive: Actor.Receive
  }

  object Adapter {

    def apply(actorRef: ActorRef): Adapter = {

      case class Msg(f: () => Unit)

      new Adapter {

        val inReceive = new InReceive {
          def apply[A](f: => A) = {
            val f1 = () => { f; () }
            actorRef.tell(Msg(f1), actorRef)
          }
        }

        val receive = { case Msg(f) => f() }
      }
    }
  }
}
