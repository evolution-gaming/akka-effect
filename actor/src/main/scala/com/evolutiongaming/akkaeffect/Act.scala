package com.evolutiongaming.akkaeffect

import akka.actor.{Actor, ActorRef}
import cats.effect.Sync
import cats.implicits._
import com.evolutiongaming.akkaeffect.AkkaEffectHelper._
import com.evolutiongaming.akkaeffect.util.Serial
import com.evolutiongaming.catshelper.CatsHelper._
import com.evolutiongaming.catshelper.{FromFuture, ToFuture, ToTry}

import scala.concurrent.{Future, Promise}
import scala.util.Try

/**
  * Act executes function in `receive` thread of an actor
  */
private[akkaeffect] trait Act[F[_]] {

  def apply[A](f: => A): F[A]
}


private[akkaeffect] object Act {

  def now[F[_]: Sync]: Act[F] = new Act[F] {
    def apply[A](f: => A) = Sync[F].delay { f }
  }


  def of[F[_]: Sync: ToFuture: FromFuture: ToTry]: F[Act[F]] = {
    Serial.of[F].map { serially => apply(serially) }
  }


  def apply[F[_]: Sync: ToTry](serial: Serial[F]): Act[F] = new Act[F] {
    def apply[A](f: => A) = {
      serial { Sync[F].delay { f } }
        .toTry
        .get
    }
  }


  implicit class ActFutureOps(val self: Act[Future]) extends AnyVal {

    def toSafe[F[_]: FromFuture]: Act[F] = new Act[F] {
      def apply[A](f: => A) = FromFuture[F].apply { self(f) }
    }
  }


  trait Adapter {

    def value: Act[Future]

    def receive(receive: Actor.Receive): Actor.Receive

    def sync[A](f: => A): A
  }

  object Adapter {

    private val threadLocal: ThreadLocal[Option[Adapter]] = new ThreadLocal[Option[Adapter]] {
      override def initialValue() = none[Adapter]
    }

    def apply(actorRef: ActorRef): Adapter = {
      val tell = actorRef.tell(_, ActorRef.noSender)
      apply(tell)
    }


    def apply(tell: Any => Unit): Adapter = {

      case class Msg(f: () => Unit)

      new Adapter { self =>

        def syncReceive(receive: Actor.Receive): Actor.Receive = new Actor.Receive {

          def isDefinedAt(a: Any) = receive.isDefinedAt(a)

          def apply(a: Any) = sync { receive(a) }
        }

        def value = new Act[Future] {
          def apply[A](f: => A) = {
            if (threadLocal.get().contains(self)) {
              f.asFuture
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
          threadLocal.set(self.some)
          try f finally threadLocal.set(none)
        }
      }
    }
  }
}
