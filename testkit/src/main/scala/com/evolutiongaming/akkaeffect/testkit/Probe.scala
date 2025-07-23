package com.evolutiongaming.akkaeffect.testkit

import akka.actor.{ActorRef, Props}
import cats.effect.implicits.effectResourceOps
import cats.effect.kernel.Deferred
import cats.effect.{Async, Ref, Resource, Sync}
import cats.syntax.all.*
import com.evolutiongaming.akkaeffect.*
import com.evolutiongaming.catshelper.CatsHelper.*
import com.evolutiongaming.catshelper.{FromFuture, ToFuture}

import scala.concurrent.duration.*
import scala.reflect.ClassTag

trait Probe[F[_]] {

  def actorEffect: ActorEffect[F, Any, Any]

  def expect[A: ClassTag]: F[F[Envelope[A]]]

  def last[A: ClassTag]: F[Option[Envelope[A]]]

  def watch(actorRef: ActorRef): F[F[Unit]]
}

object Probe {

  def of[F[_]: Async: ToFuture: FromFuture](
    actorRefOf: ActorRefOf[F],
  ): Resource[F, Probe[F]] = {

    type Unsubscribe = Boolean

    type Listener = Envelope[Any] => F[Unsubscribe]

    def receiveOf(listenersRef: Ref[F, Set[Listener]]): ReceiveOf[F, Envelope[Any], Boolean] = { actorCtx =>
      Receive[Envelope[Any]] { a =>
        a.msg match {
          case Watch(actorRef) =>
            for {
              _ <- actorCtx.watch(actorRef, Terminated(actorRef))
              _ <- Sync[F].delay(a.from.tell((), ActorRef.noSender))
            } yield false

          case msg =>
            val envelope = Envelope(msg, a.from)

            for {
              listeners   <- listenersRef.get
              unsubscribe <- listeners.foldLeft(List.empty[Listener].pure[F]) { (listeners, listener) =>
                for {
                  listeners   <- listeners
                  unsubscribe <- listener(envelope)
                } yield if (unsubscribe) listener :: listeners else listeners
              }
              _ <- listenersRef.update(_ -- unsubscribe)
            } yield false
        }
      } {
        false.pure[F]
      }.pure[Resource[F, *]]
    }

    def lastRef(subscribe: Listener => F[Unit]) =
      for {
        history <- Ref[F].of(none[Envelope[Any]])
        listener = (a: Envelope[Any]) => history.set(a.some).as(false)
        _       <- subscribe(listener)
      } yield history

    def listeners = Ref[F].of(Set.empty[Listener])

    for {
      listeners <- listeners.toResource
      props      = Props(ActorOf(receiveOf(listeners)))
      actorRef  <- actorRefOf(props)
      subscribe  = (listener: Listener) => listeners.update(_ + listener)
      lastRef   <- lastRef(subscribe).toResource
    } yield {

      val ask = Ask
        .fromActorRef(actorRef)
        .narrow[Watch, Unit](_.castM[F, Unit])

      val timeout = 1.second

      new Probe[F] {

        val actorEffect = ActorEffect.fromActor(actorRef)

        def expect[A: ClassTag] =
          for {
            deferred <- Deferred[F, Envelope[Any]]
            listener  = (a: Envelope[Any]) => deferred.complete(a).as(true)
            _        <- subscribe(listener)
          } yield deferred.get
            .flatMap(_.cast[F, A])

        def last[A: ClassTag] =
          lastRef.get
            .flatMap { envelope =>
              envelope.traverse(envelope => envelope.msg.castM[F, A].map(a => envelope.copy(msg = a)))
            }

        def watch(target: ActorRef) = {

          def listenerOf(deferred: Deferred[F, Unit]) = { (a: Envelope[Any]) =>
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
          } yield deferred.get
        }
      }
    }
  }

  final private case class Watch(actorRef: ActorRef)

  final private case class Terminated(actorRef: ActorRef)
}
