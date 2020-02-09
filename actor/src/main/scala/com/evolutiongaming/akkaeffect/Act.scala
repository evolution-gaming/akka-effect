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
// TODO  ass F
private[akkaeffect] trait Act {

  // TODO return F[A]
  def apply[A](f: => A): Future[A]
}


private[akkaeffect] object Act {

  val now: Act = new Act {
    def apply[A](f: => A) = Future.fromTry(Try(f))
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


  def adapter(actorRef: ActorRef): Adapter = {
    val tell = actorRef.tell(_, ActorRef.noSender)
    adapter(tell)
  }


  def adapter(tell: Any => Unit): Adapter = {

    case class Msg(f: () => Unit)

    val actorThread = new ThreadLocal[Boolean] {
      override def initialValue() = false
    }

    new Adapter {

      def syncReceive(receive: Actor.Receive): Actor.Receive = new Actor.Receive {

        def isDefinedAt(a: Any) = receive.isDefinedAt(a)

        def apply(a: Any) = sync { receive(a) }
      }

      val value = new Act {
        def apply[A](f: => A) = {
          if (actorThread.get()) {
            Future.successful(f)
          } else {
            val promise = Promise[A]
            val f1 = () => {
              val a = Try(f)
              promise.complete(a)
              a.void.get
            }
            tell(Msg(f1))
            promise.future
          }
        }
      }

      def receive(receive: Actor.Receive) = {
        val receiveMsg: Actor.Receive = { case Msg(f) => f() }
        syncReceive(receiveMsg orElse receive)
      }

      def sync[A](f: => A) = {
        actorThread.set(true)
        try f finally actorThread.set(false)
      }
    }
  }


  implicit class ActOps(val self: Act) extends AnyVal {

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

    def ask[F[_] : Async : ToTry, A](fa: F[A]): F[F[A]] = {

      def ask(d: Deferred[F, F[A]]) = self {
        fa
          .attempt
          .flatMap { a => d.complete(a.liftTo[F]) }
          .toTry
          .get
      }

      for {
        d <- Deferred.uncancelable[F, F[A]] // TODO deprecate usage of Deferred
        _ <- Sync[F].delay { ask(d) }
      } yield {
        d.get.flatten
      }
    }


    def ask4[F[_] : FromFuture, A](f: => A): F[A] = {
      FromFuture[F].apply { self(f) }
    }
  }

  // TODO avoid context switching when Act called from receive
  trait Adapter {

    def value: Act

    def receive(receive: Actor.Receive): Actor.Receive

    def sync[A](f: => A): A
  }
}
