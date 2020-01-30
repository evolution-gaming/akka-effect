package com.evolutiongaming.akkaeffect

import akka.actor.{Actor, ActorRef}
import cats.effect.{Async, Sync}
import cats.implicits._
import com.evolutiongaming.catshelper.FromFuture

import scala.concurrent.{Future, Promise}
import scala.util.Try

/**
  * executes function in `receive` thread of an actor
  */
private[akkaeffect] trait Act {

  def apply[A](f: => A): Unit
}


private[akkaeffect] object Act {

  def adapter(actorRef: ActorRef): Adapter[Act] = {

    case class Msg(f: () => Unit)

    val act = new Act {
      def apply[A](f: => A) = {
        val f1 = () => { f; () }
        actorRef.tell(Msg(f1), actorRef)
      }
    }

    val receive: Actor.Receive = { case Msg(f) => f() }

    Adapter(act, receive)
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

    def ask2[A](f: => A): Future[A] = {
      val promise = Promise[A]()
      self {
        val result = Try { f }
        promise.complete(result)
        result.get
      }
      promise.future
    }

    def ask3[F[_] : Sync : FromFuture, A](f: => A): F[F[A]] = {
      Sync[F]
        .delay { ask2 { f } }
        .map { a => FromFuture[F].apply { a } }
    }
  }
}
