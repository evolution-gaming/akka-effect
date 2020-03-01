package com.evolutiongaming.akkaeffect.eventsourcing

import cats.data.{NonEmptyList => Nel}
import cats.effect.{Concurrent, Resource}
import cats.implicits._
import com.evolutiongaming.akkaeffect.AkkaEffectHelper._
import com.evolutiongaming.akkaeffect.persistence.{Append, SeqNr}
import com.evolutiongaming.akkaeffect.{Receive, Reply, Serial, SerialRef}
import com.evolutiongaming.catshelper.{FromFuture, ToFuture}

// TODO store snapshot in scope of persist queue or expose seqNr
// TODO expose dropped commands because of stop, etc
object ReceiveFromReceiveCmd {

  def apply[F[_] : Concurrent : ToFuture : FromFuture, S, C, E, R](
    state: S,
    seqNr: SeqNr,
    append: Append[F, E],
    receiveCmd: ReceiveCmd[F, S, C, E]
  ): Resource[F, Receive[F, C, R]] = {

    case class State(value: S, seqNr: SeqNr)

    case class EventsAndEffect(events: List[Nel[E]], effect: Option[Throwable] => F[Unit])

    def batch(effect: Serial[F]) = {
      Batch[F].of(none[Throwable]) { (error, eventsAndEffects: Nel[EventsAndEffect]) =>
        error
          .fold {
            eventsAndEffects
              .toList
              .flatMap { _.events }
              .toNel.fold {
              none[Throwable].pure[F]
            } { events =>
              append(events)
                .flatten
                .as(none[Throwable])
                .handleError { _.some }
            }
          } { error =>
            error.some.pure[F]
          }
          .flatMap { error =>
            effect { eventsAndEffects.foldMapM { _.effect(error) } }.as((error, ()))
          }
      }
    }

    val result = for {
      stateRef <- SerialRef[F].of(State(state, seqNr))
      effect   <- Serial.of[F]
      store    <- batch(effect)
    } yield {
      new Receive[F, C, R] {

        def apply(msg: C, reply: Reply[F, R]) = {

          val result = for {
            validate <- receiveCmd(msg)
            result   <- stateRef.modify { state =>

              def stateOf(change: Change[S, E]) = {
                val seqNr = change.events.foldLeft(state.seqNr) { _ + _.size }
                State(change.state, seqNr)
              }

              for {
                directive       <- validate(state.value, state.seqNr)
                change           = directive.change
                state1           = change.fold(state) { change => stateOf(change) }
                events           = change.fold(List.empty[Nel[E]]) { _.events.toList }
                effect           = (error: Option[Throwable]) => directive.effect(error.toLeft(state1.seqNr))
                eventsAndEffect  = EventsAndEffect(events, effect)
                result          <- store(eventsAndEffect)
              } yield {
                (state1, result)
              }
            }
            result <- result
            result <- result
          } yield result

          // TODO refactor
          for {
            _ <- result.startNow
          } yield {
            false // TODO wrong
          }
        }
      }
    }
    Resource.liftF(result)
  }
}
