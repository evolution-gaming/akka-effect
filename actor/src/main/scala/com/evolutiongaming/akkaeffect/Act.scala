package com.evolutiongaming.akkaeffect

import akka.actor.{Actor, ActorRef}
import cats.effect.{Async, Sync}
import cats.syntax.all.*
import com.evolutiongaming.catshelper.CatsHelper.*
import com.evolutiongaming.catshelper.{Serial, ToTry}

/** Act executes function in `receive` thread of an actor
  */
private[akkaeffect] trait Act[F[_]] {

  def apply[A](f: => A): F[A]

  def postStop(): Unit
}

private[akkaeffect] object Act {

  def empty[F[_]: Sync]: Act[F] = {
    class Empty
    new Empty with Act[F] {
      def apply[A](f: => A) = Sync[F].delay(f)

      def postStop(): Unit = {}
    }
  }

  def serial[F[_]: Async: ToTry]: F[Act[F]] =
    Serial
      .of[F]
      .map { serial =>
        class Serial
        new Serial with Act[F] {
          def apply[A](f: => A) =
            serial(Sync[F].delay(f)).toTry.get

          def postStop(): Unit = {}
        }
      }

  sealed trait AdapterLike

  trait Adapter[F[_]] extends AdapterLike {

    def value: Act[F]

    def receive(receive: Actor.Receive): Actor.Receive

    def sync[A](f: => A): A
  }

  object Adapter {

    private[akkaeffect] def stoppedError = ActorStoppedError("actor already stopped, no more operations are possible")

    private val threadLocal: ThreadLocal[Option[AdapterLike]] = new ThreadLocal[Option[AdapterLike]] {
      override def initialValue() = none[AdapterLike]
    }

    def apply[F[_]: Async](actorRef: => ActorRef): Adapter[F] = {
      val tell = actorRef.tell(_, ActorRef.noSender)
      apply(tell)
    }

    def apply[F[_]: Async](tell: Any => Unit): Adapter[F] = {

      case class Msg(f: () => Unit)

      new Adapter[F] { self =>
        private val selfOpt = self.some

        def syncReceive(receive: Actor.Receive): Actor.Receive = new Actor.Receive {

          def isDefinedAt(a: Any) = receive.isDefinedAt(a)

          def apply(a: Any) = sync(receive(a))
        }

        val value = {
          class Main
          new Main with Act[F] {

            // flag used to prevent async (i.e. `tell` to self ) operations after actor is stopped
            // because they will be lost as message will goto dead 
            @volatile var stopped = false

            def postStop(): Unit =
              stopped = true

            def apply[A](f: => A): F[A] =
              for {
                adapter <- Sync[F].delay(threadLocal.get())
                result <- {
                  if (adapter.contains(self: Adapter[F])) {
                    Sync[F].delay(f)
                  } else if (stopped) {
                    stoppedError.raiseError[F, A]
                  } else {
                    Async[F].async[A] { callback =>
                      val f1 = () => {
                        val a = Either.catchNonFatal(f)
                        callback(a)
                        a match {
                          case Right(_) =>
                          case Left(a)  => throw a
                        }
                      }
                      Async[F].delay {
                        tell(Msg(f1))
                        Async[F].unit.some
                      }
                    }
                  }
                }
              } yield result
          }
        }

        def receive(receive: Actor.Receive) = {
          val receiveMsg: Actor.Receive = { case Msg(f) => f() }
          syncReceive(receiveMsg orElse receive)
        }

        def sync[A](f: => A) = {
          threadLocal.set(selfOpt)
          try f
          finally threadLocal.set(None)
        }
      }
    }
  }
}
