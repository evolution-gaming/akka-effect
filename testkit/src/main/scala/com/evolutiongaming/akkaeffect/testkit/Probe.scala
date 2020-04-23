package com.evolutiongaming.akkaeffect.testkit

import akka.actor.{ActorRef, Props}
import cats.effect.concurrent.{Deferred, Ref}
import cats.effect.{Concurrent, Resource, Sync}
import cats.implicits._
import com.evolutiongaming.akkaeffect.AkkaEffectHelper._
import com.evolutiongaming.akkaeffect._
import com.evolutiongaming.catshelper.CatsHelper._
import com.evolutiongaming.catshelper.{FromFuture, ToFuture}

import scala.concurrent.duration._


trait Probe[F[_]] {
  import Probe._

  def actorEffect: ActorEffect[F, Any, Any]

  def expect: F[F[Envelop]]

  def last: F[Option[Envelop]]

  def watch(actorRef: ActorRef): F[F[Unit]]
}

object Probe {

  final case class Envelop(msg: Any, from: ActorRef)


  def of[F[_] : Concurrent : ToFuture : FromFuture](
    actorRefOf: ActorRefOf[F]
  ): Resource[F, Probe[F]] = {

    final case class Watch(actorRef: ActorRef)

    final case class Terminated(actorRef: ActorRef)


    type Unsubscribe = Boolean

    type Listener = Envelop => F[Unsubscribe]


    def receiveOf(listenersRef: Ref[F, Set[Listener]]): ReceiveAnyOf[F] = {
      actorCtx: ActorCtx[F] => {
        val receive = ReceiveAny[F] { (msg, sender) =>
          msg match {
            case Watch(actorRef) =>
              for {
                _ <- actorCtx.watch(actorRef, Terminated(actorRef))
                _ <- Sync[F].delay { sender.tell((), ActorRef.noSender) }
              } yield false

            case msg =>
              val envelop = Envelop(msg, sender)

              for {
                listeners   <- listenersRef.get
                unsubscribe <- listeners.foldLeft(List.empty[Listener].pure[F]) { (listeners, listener) =>
                  for {
                    listeners   <- listeners
                    unsubscribe <- listener(envelop)
                  } yield {
                    if (unsubscribe) listener :: listeners else listeners
                  }
                }
                _           <- listenersRef.update { _ -- unsubscribe }
              } yield false
          }
        }
        receive
          .some
          .pure[Resource[F, *]]
      }
    }

    def lastRef(subscribe: Listener => F[Unit]) = {
      for {
        history  <- Ref[F].of(none[Envelop])
        listener  = (a: Envelop) => history.set(a.some).as(false)
        _        <- subscribe(listener)
      } yield history
    }

    def listeners = Ref[F].of(Set.empty[Listener])

    for {
      listeners <- listeners.toResource
      props      = Props(ActorOf(receiveOf(listeners)))
      actorRef  <- actorRefOf(props)
      subscribe  = (listener: Listener) => listeners.update { _ + listener }
      lastRef   <- lastRef(subscribe).toResource
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
