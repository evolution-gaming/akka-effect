package com.evolutiongaming.akkaeffect.persistence

import akka.actor.Actor
import akka.persistence.{Snapshotter => _}
import cats.effect.concurrent.{Deferred, Ref}
import cats.effect.implicits._
import cats.effect.{Concurrent, Resource, Sync}
import cats.implicits._
import com.evolutiongaming.akkaeffect.{Act, Adapter}
import com.evolutiongaming.catshelper.CatsHelper._
import com.evolutiongaming.catshelper.ToTry


trait Call[F[_], A, B] {

  def apply(f: => A): F[(A, F[B])]
}

object Call {

  def adapter[F[_] : Concurrent : ToTry, A, B](
    act: Act,
    stopped: F[B])(
    pf: PartialFunction[Any, (A, F[B])]
  ): Resource[F, Adapter[Call[F, A, B]]] = {

    type Callback = F[B] => F[Unit]

    Resource
      .make {
        Ref[F].of(Map.empty[A, Callback])
      } { ref =>
        ref
          .getAndSet(Map.empty)
          .flatMap { callbacks =>
            callbacks
              .values
              .toList
              .toNel
              .foldMapM { callbacks =>
                for {
                  stopped <- stopped.attempt
                  result  <- callbacks.foldMapM { _.apply(stopped.liftTo[F]) }
                } yield result
              }
          }
      }
      .map { ref =>

        def callbackOf(key: A) = {
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
              callback <- callbackOf(key)
            } yield {
              ref
                .update { _ - key }
                .productR { callback(value) }
            }
          }
        }

        val result = new Call[F, A, B] {

          def apply(f: => A) = {
            Deferred[F, F[B]]
              .flatMap { deferred =>
                act
                  .ask {
                    Sync[F]
                      .delay { f }
                      .flatMap { key =>
                        val callback: Callback = a => deferred.complete(a)
                        ref
                          .update { _.updated(key, callback) }
                          .as(key)
                      }
                  }
                  .flatten
                  .map { key =>
                    (key, deferred.get.flatten)
                  }
              }
              .uncancelable
          }
        }

        val receive: Actor.Receive = { case Expected(fa) => fa.toTry.get }

        Adapter(result, receive)
      }
  }
}
