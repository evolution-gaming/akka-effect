package com.evolutiongaming.akkaeffect.persistence

import cats.FlatMap
import cats.implicits._
import com.evolutiongaming.akkaeffect.{Convert, Receive}

trait Recovering[F[_], S, C, E, R] {

  def initial: F[S]

  /**
    * Used to replay events during recovery against passed state, resource will be released when recovery is completed
    */
  // TODO Resource
  def replay: Replay[F, S, E]

  /**
    * Called when recovery completed, resource will be released upon actor termination
    */
  def recoveryCompleted(state: S, seqNr: SeqNr): F[Receive[F, C, R]] // TODO resource


  /*final def mapEvent[EE](fee: E => EE, fe: EE => E): Recovering[S, C, EE] = new Recovering[S, C, EE] {

    def state = self.state

    def eventHandler(state: S, event: EE, seqNr: SeqNr) = self.eventHandler(state, fe(event), seqNr)

    def onCompleted(state: S, seqNr: SeqNr) = self.onCompleted(state, seqNr).mapEvent(fee)

    def onStopped(state: S, seqNr: SeqNr) = self.onStopped(state, seqNr)
  }


  final def map[CC, EE](fc: CC => C, fee: E => EE, fe: EE => E): Recovering[S, CC, EE] = new Recovering[S, CC, EE] {

    def state = self.state

    def eventHandler(state: S, event: EE, seqNr: SeqNr) = self.eventHandler(state, fe(event), seqNr)

    def onCompleted(state: S, seqNr: SeqNr) = self.onCompleted(state, seqNr).map(fc, fee)

    def onStopped(state: S, seqNr: SeqNr) = self.onStopped(state, seqNr)
  }

  final def mapBehavior[CC](f: PersistentBehavior[C, E] => PersistentBehavior[CC, E]): Recovering[S, CC, E] = new Recovering[S, CC, E] {

    def state = self.state

    def eventHandler(state: S, event: E, seqNr: SeqNr) = self.eventHandler(state, event, seqNr)

    def onCompleted(state: S, seqNr: SeqNr) = f(self.onCompleted(state, seqNr))

    def onStopped(state: S, seqNr: SeqNr) = self.onStopped(state, seqNr)
  }*/
}

object Recovering {

  implicit class RecoveringOps[F[_], S, C, E, R](val self: Recovering[F, S, C, E, R]) extends AnyVal {

    def convert[S1, C1, E1, R1](implicit
      F: FlatMap[F],
      sToS1: Convert[F, S1, S],
      s1ToS: Convert[F, S, S1],
      cc: Convert[F, C1, C],
      ec: Convert[F, E1, E]
    ): Recovering[F, S1, C1, E1, R1] = {

      /*new Recovering[F, S1, C1, E1, R1] {

        def initial = self.initial

        def replay = self.replay.convert

        def recoveryCompleted(state: S1, seqNr: SeqNr) = {
          for {
            state   <- state.convert[F, S]
            receive <- self.recoveryCompleted(state, seqNr)
          } yield {
            receive.convert
          }
        }
      }*/
      ???
    }

    def typeless(
      sf: Any => F[S],
      cf: Any => F[C],
      ef: Any => F[E])(implicit
      F: FlatMap[F]
    ): Recovering[F, Any, Any, Any, Any] = new Recovering[F, Any, Any, Any, Any] {

      val initial = self.initial.asInstanceOf[F[Any]]

      val replay = self.replay.typeless(sf, ef)

      def recoveryCompleted(state: Any, seqNr: SeqNr) = {
        for {
          state   <- sf(state)
          receive <- self.recoveryCompleted(state, seqNr)
        } yield {
          receive.typeless(cf)
        }
      }
    }
  }
}
