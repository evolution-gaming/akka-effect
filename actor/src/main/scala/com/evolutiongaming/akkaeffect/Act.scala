package com.evolutiongaming.akkaeffect

import akka.actor.{Actor, ActorRef}
import cats.effect.concurrent.Deferred
import cats.effect.{Concurrent, Sync}
import cats.implicits._
import com.evolutiongaming.catshelper.CatsHelper._
import com.evolutiongaming.catshelper.{FromFuture, ToTry}

import scala.concurrent.{Future, Promise}
import scala.util.Try

/**
  * executes function in `receive` thread of an actor
  */
private[akkaeffect] trait Act {

  def apply[A](f: => A): Unit
}


private[akkaeffect] object Act {

  val now: Act = new Act {
    def apply[A](f: => A) = { val _ = f }
  }

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

    def ask[F[_] : Concurrent : ToTry, A](fa: F[A]): F[F[A]] = {
      Deferred[F, F[A]]
        .flatMap { deferred =>
          Sync[F]
            .delay {
              self {
                fa
                  .attempt
                  .flatMap { a => deferred.complete(a.liftTo[F]) }
                  .toTry
                  .get
              }
            }
            .as {
              deferred
                .get
                .flatten
            }
        }
    }
  }
}
