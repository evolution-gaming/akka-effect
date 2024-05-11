package com.evolutiongaming.akkaeffect.persistence

import cats.effect.Sync
import cats.effect.kernel.Ref
import cats.syntax.all._

trait ReplayStateful[F[_], S, E] {

  def state: F[S]

  def replay: Replay[F, E]
}

object ReplayStateful {

  def of[F[_]: Sync, S, E](initial: S)(f: (S, E, SeqNr) => F[S]): F[ReplayStateful[F, S, E]] =
    Ref[F]
      .of(initial)
      .map { stateRef =>
        new ReplayStateful[F, S, E] {

          val state = stateRef.get

          val replay = Replay[E] { (event, seqNr) =>
            for {
              s <- stateRef.get
              s <- f(s, event, seqNr)
              _ <- stateRef.set(s)
            } yield {}
          }
        }
      }
}
