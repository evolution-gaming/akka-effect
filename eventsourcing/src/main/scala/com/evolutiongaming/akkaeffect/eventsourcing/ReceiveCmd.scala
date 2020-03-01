package com.evolutiongaming.akkaeffect.eventsourcing

import cats.{FlatMap, Monad, ~>}
import com.evolutiongaming.akkaeffect.{Receive, Reply}

trait ReceiveCmd[F[_], S, C, E] {

  def apply(cmd: C): F[Validate[F, S, E]]
}

object ReceiveCmd {

  implicit class ReceiveCmdOps[F[_], S, A, B](val self: ReceiveCmd[F, S, A, B]) extends AnyVal {

    def mapK[G[_]](fg: F ~> G, gf: G ~> F): ReceiveCmd[G, S, A, B] = (cmd: A) => ???


    def collect[AA](f: AA => F[Option[A]])(implicit F: Monad[F]): ReceiveCmd[F, S, AA, B] = {
      new ReceiveCmd[F, S, AA, B] {
        
        def apply(cmd: AA) = ???
      }
    }


    def convert[A1, B1](
      af: A1 => F[A],
      bf: B => F[B1])(implicit
      F: FlatMap[F],
    ): ReceiveCmd[F, S, A1, B1] = {
      ???
    }


    def convertMsg[A1](f: A1 => F[A])(implicit F: FlatMap[F]): ReceiveCmd[F, S, A1, B] = {
      ???
    }


    def widen[A1 >: A, B1 >: B](f: A1 => F[A])(implicit F: FlatMap[F]): ReceiveCmd[F, S, A1, B1] = {
      ???
    }


    def typeless(f: Any => F[A])(implicit F: FlatMap[F]): ReceiveCmd[F, S, Any, Any] = widen(f)
  }
}