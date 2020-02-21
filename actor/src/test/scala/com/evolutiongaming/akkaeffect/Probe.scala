package com.evolutiongaming.akkaeffect

import akka.actor.{Actor, ActorRef, Props}
import cats.effect.concurrent.{Deferred, Ref}
import cats.effect.{Concurrent, Resource, Sync}
import cats.implicits._
import com.evolutiongaming.akkaeffect.AkkaEffectHelper._
import com.evolutiongaming.catshelper.CatsHelper._
import com.evolutiongaming.catshelper.{FromFuture, SerialRef, ToFuture}

import scala.concurrent.duration._


trait Probe[F[_]] {
  import Probe._

  def actorEffect: ActorEffect[F, Any, Any]

  def expect: F[F[Envelop]]

  def last: F[Option[Envelop]]

  def watch(actorRef: ActorRef): F[F[Unit]]
}

object Probe {

  final case class Envelop(msg: Any, sender: ActorRef)


  def of[F[_] : Concurrent : ToFuture : FromFuture](
    actorRefOf: ActorRefOf[F]
  ): Resource[F, Probe[F]] = {

    final case class Watch(actorRef: ActorRef)

    final case class Terminated(actorRef: ActorRef)

    type Unsubscribe = Boolean

    type Rcv = (Any, ActorRef) => F[Unit]

    type Listener = Envelop => F[Unsubscribe]

    def actor(receiveOf: ActorCtx[F, Any, Any] => Rcv): Actor = {

      def onPreStart(ctx: ActorCtx[F, Any, Any]): Resource[F, Option[Rcv]] = {
        receiveOf(ctx)
          .some
          .pure[Resource[F, *]]
      }

      def onReceive(a: Any, self: ActorRef, sender: ActorRef) = {
        state: Rcv =>
          state(a, sender)
            .adaptError { case error =>
              ActorError(s"$self.receive failed on $a from $sender with $error", error)
            }
            .as(false)
      }

      new Actor {

        val act = Act.adapter(self)

        val actorVar = ActorVar[F, Rcv](act.value, context)

        override def preStart(): Unit = {
          super.preStart()
          act.sync {
            val ctx = ActorCtx[F](act.value.fromFuture, context)
            actorVar.preStart {
              onPreStart(ctx)
            }
          }
        }

        def receive: Receive = act.receive {
          case a => actorVar.receive { onReceive(a, self = self, sender = sender()) }
        }

        override def postStop(): Unit = {
          act.sync {
            actorVar.postStop().toFuture
          }
          super.postStop()
        }
      }
    }


    def receiveOf(listeners: SerialRef[F, Set[Listener]]): (ActorCtx[F, Any, Any] => Rcv) = {
      ctx: ActorCtx[F, Any, Any] => {
        (msg: Any, sender: ActorRef) => {

          msg match {
            case Watch(actorRef) =>
              for {
                _ <- ctx.watch(actorRef, Terminated(actorRef))
                _ <- Sync[F].delay { sender.tell((), ActorRef.noSender) }
              } yield {}

            case msg =>
              val envelop = Envelop(msg, sender)
              listeners.update { listeners =>
                listeners.foldLeft(listeners.pure[F]) { (listeners, listener) =>
                  for {
                    listeners   <- listeners
                    unsubscribe <- listener(envelop)
                  } yield {
                    if (unsubscribe) listeners - listener else listeners
                  }
                }
              }
          }
        }
      }
    }

    def lastRef(subscribe: Listener => F[Unit]) = {
      for {
        history  <- Ref[F].of(none[Envelop])
        listener  = (a: Envelop) => history.set(a.some).as(false)
        _        <- subscribe(listener)
      } yield history
    }

    def listeners = SerialRef[F].of(Set.empty[Listener])

    for {
      listeners <- Resource.liftF(listeners)
      props      = Props(actor(receiveOf(listeners)))
      actorRef  <- actorRefOf(props)
      subscribe  = (listener: Listener) => listeners.update { listeners => ((listeners + listener)).pure[F] }
      lastRef   <- Resource.liftF(lastRef(subscribe))
    } yield {

      val ask = Ask
        .fromActorRef(actorRef)
        .narrow[Watch, Unit](_.cast[F, Unit])

      val timeout = 1.second

      new Probe[F] {

        val actorEffect = ActorEffect.fromActor(actorRef)

        val expect = {
          for {
            deferred <- Deferred[F, Envelop]
            listener  = (a: Envelop) => deferred.complete(a).as(true)
            _        <- subscribe(listener)
          } yield {
            deferred.get
          }
        }

        val last = lastRef.get

        def watch(target: ActorRef) = {

          def listenerOf(deferred: Deferred[F, Unit]) = {
            a: Envelop =>
              a.msg match {
                case Terminated(`target`) => deferred.complete(()).as(true)
                case _                    => false.pure[F]
              }
          }

          for {
            deferred <- Deferred[F, Unit]
            listener  = listenerOf(deferred)
            _        <- subscribe(listener)
            _        <- ask(Watch(target), timeout).flatten
          } yield {
            deferred.get
          }
        }
      }
    }
  }
}
