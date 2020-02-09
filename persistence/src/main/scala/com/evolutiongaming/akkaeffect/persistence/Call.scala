package com.evolutiongaming.akkaeffect.persistence

import akka.actor.Actor
import akka.persistence.{Snapshotter => _}
import cats.effect.concurrent.Ref
import cats.effect.implicits._
import cats.effect.{Resource, Sync}
import cats.implicits._
import com.evolutiongaming.akkaeffect.{Act, Adapter, PromiseEffect}
import com.evolutiongaming.catshelper.CatsHelper._
import com.evolutiongaming.catshelper.{FromFuture, ToTry}

import scala.util.Try


/**
  * `Call` represents convenient way of modeling cross actor communication
  * Basically when you are within an actor calling methods which privately send messages to another actor
  * and expect you to react upon reply - you can still model that via `A => F[B]` with help of `Call`
  * `call { sendMsg(id); id }`
  */
trait Call[F[_], A, B] {

  def apply(f: => A): F[(A, F[B])]
}

object Call {

  def adapter[F[_] : Sync : ToTry : FromFuture, A, B](
    act: Act,
    stopped: F[B])(
    pf: PartialFunction[Any, (A, Try[B])]
  ): Resource[F, Adapter[Call[F, A, B]]] = {

    Resource
      .make {
        Ref[F].of(Map.empty[A, PromiseEffect[F, B]])
      } { ref =>
        ref
          .getAndSet(Map.empty)
          .flatMap { callbacks =>
            callbacks
              .values
              .toList
              .toNel
              .foldMapM { promise =>
                for {
                  stopped <- stopped.attempt
                  result  <- promise.foldMapM { _.complete(stopped) }
                } yield result
              }
          }
      }
      .map { ref =>

        def promiseOf(key: A) = {
          ref
            .get
            .map { _.get(key) }
            .toTry
            .get
        }

        object Expected {

          def unapply(a: Any): Option[F[Unit]] = {
            for {
              (key, value) <- pf.lift(a)
              promise      <- promiseOf(key)
            } yield {
              ref
                .update { _ - key }
                .productR { promise.complete(value) }
            }
          }
        }

        val call = new Call[F, A, B] {

          def apply(f: => A) = {
            PromiseEffect[F, B]
              .flatMap { promise =>
                act
                  .ask4 {
                    val key = f
                    ref
                      .update { _.updated(key, promise) }
                      .toTry
                      .get
                    key
                  }
                  .map { key => (key, promise.get) }
              }
              .uncancelable
          }
        }

        val receive: Actor.Receive = { case Expected(fa) => fa.toTry.get }

        Adapter(call, receive)
      }
  }
}
