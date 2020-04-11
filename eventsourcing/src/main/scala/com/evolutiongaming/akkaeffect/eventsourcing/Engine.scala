package com.evolutiongaming.akkaeffect.eventsourcing

import cats.data.{NonEmptyList => Nel}
import cats.effect.{Concurrent, Resource}
import cats.implicits._
import com.evolutiongaming.akkaeffect.persistence.{Append, SeqNr}
import com.evolutiongaming.akkaeffect.{Serial, SerialRef}
import com.evolutiongaming.catshelper.{FromFuture, ToFuture}

// TODO store snapshot in scope of persist queue or expose seqNr
// TODO expose dropped commands because of stop, etc

// TODO Test
/**
  * Executes following stages
  * 1. Validation: serially validates and changes current state
  * 2. Append: appends events produced in #1 in batches, might be blocked by previous in flight batch
  * 3. Effect: serially performs side effects
  *
  * Different stages do not block each other
  * Ordering is strictly preserved between elements - means that first validated element will be also the first to append events and to perform side effects
  */
trait Engine[F[_], S, E] {

  def state: F[Engine.State[S]]

  def apply(validate: Validate[F, S, E]): F[F[Unit]] // TODO replace Unit
}

object Engine {

  def of[F[_]: Concurrent: ToFuture: FromFuture, S, E](
    initial: State[S],
    append: Append[F, E],
  ): Resource[F, Engine[F, S, E]] = {

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
      stateRef <- SerialRef[F].of(initial)
      effect   <- Serial.of[F]
      append   <- batch(effect)
    } yield {
      new Engine[F, S, E] {

        def state = stateRef.get

        def apply(validate: Validate[F, S, E]) = {
          stateRef
            .modify { state =>

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
                result          <- append(eventsAndEffect)
              } yield {
                (state1, result)
              }
            }
            .map { _.flatten }
        }
      }
    }
    Resource.liftF(result)
  }


  final case class State[A](value: A, seqNr: SeqNr)
}