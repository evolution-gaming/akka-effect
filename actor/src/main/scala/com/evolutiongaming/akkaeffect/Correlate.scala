package com.evolutiongaming.akkaeffect

import cats.effect.concurrent.Ref
import cats.effect.implicits._
import cats.effect.{Async, Concurrent, IO, Resource, Timer}
import cats.syntax.all._
import com.evolutiongaming.akkaeffect.util.PromiseEffect
import com.evolutiongaming.catshelper.CatsHelper._
import com.evolutiongaming.catshelper.{FromFuture, ToFuture, ToTry}

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.util.Try

/**
  * `Correlate` represents convenient way of modeling cross actor communication
  * Basically when you are within an actor calling methods which send messages to another actor
  * and expect you to react upon reply - you can still model that via `A => F[B]` with help of `Correlate`
  */
trait Correlate[F[_], A, B] {
  import Correlate._

  def call: Call[F, A, B]

  def callback: Callback[F, A, B]
}

object Correlate {

  def of[F[_]: Concurrent: Timer: FromFuture, A, B](released: F[B]): Resource[F, Correlate[F, A, B]] = {
    Resource
      .make {
        Ref[F].of(Map.empty[A, PromiseEffect[F, B]].some)
      } { ref =>
        ref
          .getAndSet(none)
          .flatMap { callbacks =>
            callbacks.foldMapM { callbacks =>
              callbacks
                .values
                .toList
                .toNel
                .foldMapM { promise =>
                  for {
                    released <- released.attempt
                    result   <- promise.foldMapM { _.complete(released) }
                  } yield result
                }
            }
          }
      }
      .map { ref =>

        new Correlate[F, A, B] {

          val call = {
            (a: A, timeout: FiniteDuration) => {
              PromiseEffect.of[F, B].flatMap { promise =>
                ref
                  .update { _.map { _.updated(a, promise) } }
                  .as(promise.get.timeout(timeout))
              }
            }
          }

          val callback = {
            (key: A, value: Either[Throwable, B]) => {
              ref
                .modify {
                  case Some(map) =>
                    map
                      .get(key)
                      .fold {
                        (map.some, false.pure[F])
                      } { promise =>
                        ((map - key).some, promise.complete(value).as(true))
                      }

                  case None => (none, released.as(false))
                }
                .flatten
            }
          }
        }
      }
  }


  trait Unsafe[A, B] {

    def call: Unsafe.Call[A, B]

    def callback: Unsafe.Callback[A, B]
  }

  object Unsafe {

    def of[A, B](
      released: => Try[B])(implicit
      concurrent: Concurrent[IO],
      timer: Timer[IO]
    ): (Unsafe[A, B], () => Unit) = {
      implicit val fromFuture = FromFuture.lift[IO](Async[IO], ParasiticExecutionContext())
      val released1 = IO { released.liftTo[IO] }.flatten
      val (correlate, release) = Correlate
        .of[IO, A, B](released1)
        .allocated
        .toTry
        .get
      (apply(correlate), () => release.toTry.get)
    }


    def apply[F[_] : ToFuture : ToTry, A, B](
      correlate: Correlate[F, A, B]
    ): Unsafe[A, B] = new Unsafe[A, B] {

      val call: Call[A, B] = {
        val call = correlate.call
        (key: A, timeout: FiniteDuration) => {
          call(key, timeout)
            .toTry
            .fold(a => Future.failed(a), _.toFuture)
        }
      }

      val callback: Callback[A, B] = {
        val callback = correlate.callback
        (key: A, value: Either[Throwable, B]) => {
          callback(key, value)
            .toTry
            .get
        }
      }
    }


    trait Call[A, B] {

      def apply(key: A, timeout: FiniteDuration): Future[B]
    }


    trait Callback[A, B] {

      def apply(key: A, value: Either[Throwable, B]): Boolean
    }
  }


  trait Call[F[_], A, B] {

    /**
      * Registers and returns promise of something to be completed
      *
      * @param key used to correlate request and response
      * @return outer F[_] is about enqueueing, inner F[_] when completed
      */
    def apply(key: A, timeout: FiniteDuration): F[F[B]]
  }


  trait Callback[F[_], A, B] {

    /**
      * Completes pending promises
      *
      * @param key used to correlate request and response
      */
    def apply(key: A, value: Either[Throwable, B]): F[Boolean]
  }


  implicit class CorrelateOps[F[_], A, B](val self: Correlate[F, A, B]) extends AnyVal {

    def toUnsafe(implicit toTry: ToTry[F], toFuture: ToFuture[F]): Unsafe[A, B] = Unsafe(self)
  }
}
