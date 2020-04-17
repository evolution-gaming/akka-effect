package com.evolutiongaming.akkaeffect

import akka.actor.{Actor, ActorRef}
import cats.effect._
import cats.implicits._
import com.evolutiongaming.akkaeffect.Fail.implicits._
import com.evolutiongaming.catshelper.CatsHelper._
import com.evolutiongaming.catshelper.{FromFuture, ToFuture}

/**
  * Creates instance of [[akka.actor.Actor]] out of [[ReceiveOf]]
  */
object ActorOf {

  def apply[F[_]: Sync: ToFuture: FromFuture](
    receiveOf: ReceiveAnyOf[F]
  ): Actor = {

    type State = ReceiveAny[F]

    def onPreStart(actorCtx: ActorCtx[F])(implicit fail: Fail[F]) = {
      receiveOf(actorCtx)
        .handleErrorWith { error =>
          s"failed to allocate receive".fail[F, Option[State]](error).toResource
        }
    }

    def onReceive(a: Any, sender: ActorRef)(implicit fail: Fail[F]) = {
      state: State =>
        state(a, sender)
          .map {
            case false => Releasable[F, State](state).some
            case true  => none[Releasable[F, State]]
          }
          .handleErrorWith { error =>
            s"failed on $a from $sender".fail[F, Option[Releasable[F, State]]](error)
          }
    }

    new Actor {

      private implicit val fail = Fail.fromActorRef[F](self)

      private val act = Act.adapter(self)

      private val actorVar = ActorVar[F, State](act.value, context)

      override def preStart(): Unit = {
        super.preStart()
        act.sync {
          val ctx = ActorCtx[F](act.value.toSafe, context)
          actorVar.preStart {
            onPreStart(ctx)
          }
        }
      }

      def receive: Receive = act.receive {
        case a => actorVar.receive { onReceive(a, sender = sender()) }
      }

      override def postStop(): Unit = {
        act.sync {
          actorVar.postStop().toFuture
        }
        super.postStop()
      }
    }
  }
}