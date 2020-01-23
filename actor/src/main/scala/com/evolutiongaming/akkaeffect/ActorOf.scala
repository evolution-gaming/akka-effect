package com.evolutiongaming.akkaeffect

import akka.actor.{Actor, ActorRef}
import cats.effect._
import cats.implicits._
import com.evolutiongaming.catshelper.{FromFuture, ToFuture}

object ActorOf {

  def apply[F[_] : Async : ToFuture : FromFuture](
    receiveOf: ReceiveOf[F, Any, Any]
  ): Actor = {

    type Rcv = Receive[F, Any, Any]

    type PostStop = F[Unit]

    type State = (Rcv, PostStop)

    val state = StateVar[F].of(none[State])

    def fail[A](msg: String, cause: Option[Throwable]) = ActorError(msg, cause).raiseError[F, A]

    def update(adapter: ActorContextAdapter[F])(f: Option[State] => F[Option[State]]): Unit = {
      state.update { state =>
        f(state).handleErrorWith { error => adapter.fail(error).as(none[State]) }
      }
    }

    def onPreStart(adapter: ActorContextAdapter[F], self: ActorRef): Unit = {
      update(adapter) {
        case None    =>
          receiveOf(adapter.ctx)
            .allocated
            .handleErrorWith { cause =>
              fail[(Option[Rcv], PostStop)](s"$self.onPreStart failed: cannot allocate receive", cause.some)
            }
            .flatMap {
              case (Some(receive), release) =>
                (receive, release).some.pure[F]

              case (None, release) =>
                release
                  .attempt
                  .productR {
                    adapter
                      .stop
                      .as(none[State])
                  }
            }
        case Some(_) =>
          fail[Option[State]](s"$self.onPreStart failed: unexpected state", none)
      }
    }

    def onAny(a: Any, adapter: ActorContextAdapter[F], self: ActorRef, sender: ActorRef): Unit = {
      update(adapter) {
        case Some(state @ (receive, _)) =>
          val reply = Reply.fromActorRef[F](to = sender, from = self.some)
          receive(a, reply).flatMap {
            case false => state.some.pure[F]
            case true  => adapter.stop.as(none[State])
          }
        case receive                    =>
          receive.pure[F]
      }
    }

    def onPostStop(): Unit = {
      state.update {
        case Some((_, release)) => release.as(none[State])
        case None               => none[State].pure[F]
      }
    }

    new Actor {

      val adapter = ActorContextAdapter(context)

      override def preStart(): Unit = {
        super.preStart()
        onPreStart(adapter, self)
      }

      def receive: Receive = adapter.receive orElse {
        case a => onAny(a, adapter, self = self, sender = sender())
      }

      override def postStop(): Unit = {
        onPostStop()
        super.postStop()
      }
    }
  }
}