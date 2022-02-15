package com.evolutiongaming.akkaeffect.util

import cats.effect.{Async, Deferred}
import cats.syntax.all._

import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec

private[akkaeffect] object DeferredUncancelable {

  def apply[F[_], A](implicit F: Async[F]): F[Deferred[F, A]] = {

    sealed abstract class S

    object S {
      final case class Set(a: A) extends S
      final case class Unset(fs: List[A => Unit]) extends S
    }

    F.delay {
      val ref = new AtomicReference(S.Unset(List.empty): S)
      new Deferred[F, A] {
        def get = {
          F.defer {
            ref.get match {
              case s: S.Set   => F.pure(s.a)
              case _: S.Unset =>
                F.async_[A] { callback =>
                  @tailrec
                  def get(): Unit = {
                    ref.get match {
                      case s: S.Set   => callback(s.a.asRight)
                      case s: S.Unset =>
                        val f = (a: A) => callback(a.asRight)
                        val s1 = S.Unset(f :: s.fs)
                        if (ref.compareAndSet(s, s1)) {} else get()
                    }
                  }

                  get()
                }
            }
          }
        }

        def complete(a: A) = {

          @tailrec
          def complete(a: A): F[Boolean] = {
            ref.get match {
              case _: S.Set   => false.pure
              case s: S.Unset =>
                if (ref.compareAndSet(s, S.Set(a))) {
                  val fs = s.fs
                  if (fs.nonEmpty) {
                    F.delay { fs.foreach { f => f(a) } }
                    var result = F.unit
                    fs.foreach { f =>
                      val task = F.void(F.start(F.delay(f(a))))
                      result = F.flatMap(result) { _ => task }
                    }
                    result.as(true)
                  } else {
                    true.pure
                  }

                } else {
                  complete(a)
                }
            }
          }

          F.defer { complete(a) }
        }

        override def tryGet: F[Option[A]] = ref.get() match {
          case S.Set(a) => a.some.pure
          case S.Unset(_) => Option.empty[A].pure
        }
      }
    }
  }
}

