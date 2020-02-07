package com.evolutiongaming.akkaeffect

import akka.actor.{Actor, ActorRef}
import cats.effect.concurrent.Deferred
import cats.effect.{Async, Concurrent, Sync}
import cats.implicits._
import com.evolutiongaming.catshelper.CatsHelper._
import com.evolutiongaming.catshelper.{FromFuture, ToFuture, ToTry}

import scala.concurrent.{Future, Promise}
import scala.util.Try

/**
  * executes function in `receive` thread of an actor
  */
private[akkaeffect] trait Act {

  // TODO return F[A]
  def apply[A](f: => A): Unit
}


private[akkaeffect] object Act {

  val now: Act = new Act {
    def apply[A](f: => A) = { val _ = f }
  }

  def of[F[_] : Async : ToTry : ToFuture]: F[Act] = {
    Serially.of[F].map { serially => apply(serially) }
  }

  def apply[F[_] : Sync : ToTry : ToFuture](serially: Serially[F]): Act = new Act {
    def apply[A](f: => A) = {
      serially { Sync[F].delay { f } }
        .toTry
        .get
        .toFuture
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

  // TODO avoid context switching when Act called from receive
  /*trait Adapter {

    def value: Act

    def around[A](f: => A): A

    def receive(receive: Actor.Receive): Actor.Receive
  }

  object Adapter {

    def adapter(actorRef: ActorRef): Adapter = {

      case class Msg(f: () => Unit)

      val receiveMsg: Actor.Receive = { case Msg(f) => f() }

      val actorThread = new ThreadLocal[Boolean] {
        override def initialValue() = false
      }

      def aroundReceive(receive: Actor.Receive) = new Actor.Receive {

        def isDefinedAt(a: Any) = receive.isDefinedAt(a)

        def apply(a: Any) = receive(a)
      }

      new Adapter {

        val value = new Act {
          def apply[A](f: => A) = {

            if (actorThread.get()) {
              f
            } else {

            }

            val f1 = () => { f; () }
            actorRef.tell(Msg(f1), actorRef)
          }
        }

        def around[A](f: => A) = {
          actorThread.set(true)
          try f finally actorThread.set(false)
        }

        def receive(receive: Actor.Receive) = {




        }
      }
    }
  }*/
}
