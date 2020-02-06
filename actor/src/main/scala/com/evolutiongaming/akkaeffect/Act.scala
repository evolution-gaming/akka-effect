package com.evolutiongaming.akkaeffect

import akka.actor.{Actor, ActorRef}
import cats.effect.concurrent.{Deferred, Ref}
import cats.effect.{Concurrent, Sync}
import cats.implicits._
import com.evolutiongaming.catshelper.CatsHelper._
import com.evolutiongaming.catshelper.{FromFuture, ToFuture, ToTry}

import scala.concurrent.{ExecutionContext, Future, Promise}
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

  def serial[F[_] : Concurrent : ToTry : ToFuture](implicit executor: ExecutionContext): F[Act] = {
    Ref[F]
      .of(Future.unit)
      .map { ref =>
        new Act {
          def apply[A](f: => A) = {

            val promise = Promise[Unit]

            val future = ref
              .modify { future => (future.productR(promise.future), future) }
              .toTry
              .get

            def complete() = {
              promise.completeWith {
                Sync[F]
                  .delay { f }
                  .void
                  .toFuture
              }
            }

            future.value match {
              case Some(_) => complete()
              case None    => future.onComplete { _ => complete() }
            }
          }
        }
      }
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

      def ask(deferred: Deferred[F, F[A]]) = self {
        fa
          .attempt
          .flatMap { a => deferred.complete(a.liftTo[F]) }
          .toTry
          .get
      }

      for {
        deferred <- Deferred[F, F[A]]
        _        <- Sync[F].delay { ask(deferred) }
      } yield {
        deferred.get.flatten
      }
    }
  }
}
