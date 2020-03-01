package com.evolutiongaming.akkaeffect.eventsourcing

import cats.data.{NonEmptyList => Nel}
import cats.effect.{Concurrent, Resource}
import cats.implicits._
import com.evolutiongaming.akkaeffect.AkkaEffectHelper._
import com.evolutiongaming.akkaeffect.persistence.{Append, SeqNr}
import com.evolutiongaming.akkaeffect.{Receive, Reply, Serial, SerialRef}
import com.evolutiongaming.catshelper.{FromFuture, ToFuture}

import scala.util.Try

// TODO store snapshot in scope of persis queue or expose seqNr
object ReceiveFromReceiveCmd {

  def apply[F[_] : Concurrent : ToFuture : FromFuture, S, C, E, R](
    state: S,
    seqNr: SeqNr,
    append: Append[F, E],
    receiveCmd: ReceiveCmd[F, S, C, E]
  ): Resource[F, Receive[F, C, R]] = {

    case class State(value: S, seqNr: SeqNr)

    val result = for {
      stateRef <- SerialRef[F].of(State(state, seqNr)) // TODO
      effect   <- Serial.of[F]
      persist  <- Aggregate[F].of(none[Throwable]) { (error, a: Nel[CmdResult.Change[F, S, E]]) =>

        def effects(seqNr: Try[SeqNr]) = for {
          result <- effect { a.foldMapM { _.callback(seqNr) } }
        } yield {
          val error = seqNr.fold(_.some,_ => none)
          (error, result)
        }

        error match {
          case None =>
            val events = a.flatMap { _.events }
            for {
              seqNr  <- append(events).flatten.attempt
              result <- effects(seqNr.liftTo[Try])
            } yield result

          case Some(error) =>
            effects(error.raiseError[Try, SeqNr])
        }
      }

    } yield {
      new Receive[F, C, R] {
        def apply(msg: C, reply: Reply[F, R]) = {

          val result = for {
            validate <- receiveCmd(msg)
            result   <- stateRef.modify { state =>
              for {
                result <- validate(state.value, state.seqNr)
                seqNr   = result.events.foldLeft(state.seqNr) { _ + _.size }
                a      <- persist(result)
              } yield {
                val state1 = state.copy(value = result.state, seqNr)
                (state1, a)
              }
            }
            result <- result
            result <- result
          } yield result

          // TODO refactor
          for {
            _ <- result.startNow
          } yield {
            false
          }
        }
      }
    }
    Resource.liftF(result)
  }
}
