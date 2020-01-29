package com.evolutiongaming.akkaeffect

import akka.actor.{Actor, ActorRef}
import cats.effect.{Async, Sync}

import scala.util.Try

/**
  * executes function in `receive` thread of an actor
  */
private[akkaeffect] trait Act {

  def apply[A](f: => A): Unit
}


private[akkaeffect] object Act {

  trait Adapter {

    def act: Act

    def receive: Actor.Receive
  }

  object Adapter {

    def apply(actorRef: ActorRef): Adapter = {

      case class Msg(f: () => Unit)

      new Adapter {

        val act = new Act {
          def apply[A](f: => A) = {
            val f1 = () => { f; () }
            actorRef.tell(Msg(f1), actorRef)
          }
        }

        val receive = { case Msg(f) => f() }
      }
    }
  }


  implicit class ActOps(val self: Act) extends AnyVal {

    // TODO rename
    def tell1[F[_] : Sync, A](f: => A): F[Unit] = {
      Sync[F].delay { self { f } }
    }

    def ask[F[_] : Async, A](f: => A): F[A] = {
      Async[F].asyncF[A] { callback =>
        self.tell1 {
          val result = Try { f }
          callback(result.toEither)
          result.get
        }
      }
    }
  }
}
