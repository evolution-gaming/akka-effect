package com.evolutiongaming.akkaeffect.persistence

import cats.effect.Sync
import cats.effect.concurrent.Ref
import cats.implicits._

trait ReplayStateful[F[_], S, E] {

  def state: F[S]

  def replay: Replay[F, E]
}

object ReplayStateful {

  def of[F[_]: Sync, S, E](initial: S)(f: (SeqNr, S, E) => F[S]): F[ReplayStateful[F, S, E]] = {
    Ref[F]
      .of(initial)
      .map { stateRef =>
        new ReplayStateful[F, S, E] {

          val state = stateRef.get

          val replay = Replay[F, E] { (seqNr, event) =>
            for {
              s <- stateRef.get
              s <- f(seqNr, s, event)
              _ <- stateRef.set(s)
            } yield {}
          }
        }
      }
  }
}
