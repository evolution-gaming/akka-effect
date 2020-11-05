package com.evolutiongaming.akkaeffect.eventsourcing

import akka.actor.ActorSystem
import akka.stream.scaladsl.{Sink, Source}
import akka.stream.{Materializer, OverflowStrategy, QueueOfferResult, SystemMaterializer}
import cats.data.{NonEmptyList => Nel}
import cats.effect.concurrent.{Deferred, Ref}
import cats.effect.implicits._
import cats.effect.{Concurrent, Fiber, Resource, Sync}
import cats.syntax.all._
import cats.{Applicative, FlatMap, Functor, Monad}
import com.evolutiongaming.akkaeffect
import com.evolutiongaming.akkaeffect.eventsourcing.util.ResourceFromQueue
import com.evolutiongaming.akkaeffect.persistence.{Events, SeqNr}
import com.evolutiongaming.akkaeffect.util.CloseOnError
import com.evolutiongaming.catshelper.CatsHelper._
import com.evolutiongaming.catshelper.{FromFuture, Runtime, ToFuture}


trait Engine[F[_], S, E] {
  import Engine._

  def state: F[State[S]]

  /**
    * @return Outer F[_] is about `load` being enqueued, this immediately provides order guarantees
    *         Inner F[_] is about `load` being completed
    */
  def apply[A](load: F[Validate[F, S, E, A]]): F[F[A]]
}

object Engine {

  val stopped: EngineError = EngineError("stopped")

  val released: EngineError = EngineError("released")


  def const[F[_]: Monad, S, E](
    initial: State[S],
    result: Either[Throwable, SeqNr]
  ): Engine[F, S, E] = new Engine[F, S, E] {

    val state = initial.pure[F]

    def apply[A](load: F[Validate[F, S, E, A]]) = {
      for {
        validate  <- load
        directive <- validate(initial.value, initial.seqNr)
        result    <- directive.effect(result)
      } yield {
        result.pure[F]
      }
    }
  }


  /**
    * Executes following stages
    * 1. Load: parallel, in case you need to call external world
    * 2. Validation: serially validates and changes current state
    * 3. Append: appends events produced in #2 in batches, might be blocked by previous in flight batch
    * 4. Effect: serially performs side effects
    *
    * Ordering is strictly preserved between elements, means that first received element will be also
    * the first to append events and to perform side effects
    *
    * Example:
    * let's consider we called engine in the the following order
    * 1. engine(A)
    * 2. engine(B)
    * there after Load stage will be performed in parallel for both A and B, however despite B being faster
    * loading it won't reach Validation stage before A, because A is first submitted element.
    * As soon as A done with validation, it will proceed with Append stage, meanwhile B can be moved
    * to Validation stage seeing already updated state by A
    */
  def of[F[_]: Concurrent: ToFuture: FromFuture, S, E](
    initial: State[S],
    actorSystem: ActorSystem,
    append: akkaeffect.persistence.Append[F, E],
  ): Resource[F, Engine[F, S, E]] = {
    for {
      materializer <- Sync[F].delay { SystemMaterializer(actorSystem).materializer }.toResource
      engine       <- of(initial, materializer, Append(append))
    } yield engine
  }


  def of[F[_]: Concurrent: Runtime: ToFuture: FromFuture, S, E](
    initial: State[S],
    materializer: Materializer,
    append: Append[F, E]
  ): Resource[F, Engine[F, S, E]] = {

    final case class State(value: Engine.State[S], stopped: Boolean)

    final case class EventsAndEffect(events: List[Nel[E]], effect: Option[Throwable] => F[Unit])


    trait Append {

      def apply(events: Events[E]): F[SeqNr]

      def error: F[Option[Throwable]]
    }

    object Append {

      def of(append: Engine.Append[F, E]): F[Append] = {
        CloseOnError
          .of[F]
          .map { closeOnError =>
            new Append {

              def apply(events: Events[E]) = closeOnError { append(events) }

              def error = closeOnError.error
            }
          }
      }
    }


    val bufferSize = 100000

    def queue(
      parallelism: Int,
      stateRef: Ref[F, State],
      append: Append
    ) = {
      val graph = Source
        .queue[F[Validate[F, S, E, Unit]]](bufferSize, OverflowStrategy.backpressure)
        .mapAsync(parallelism) { _.toFuture }
        .mapAsync(1) { validate =>
          val result = for {
            state     <- stateRef.get
            directive <- validate(state.value.value, state.value.seqNr)
            result    <- {
              if (state.stopped) {
                val effect = (error: Option[Throwable]) => {
                  directive.effect(error.getOrElse(stopped).asLeft)
                }
                EventsAndEffect(List.empty, effect).pure[F]
              } else {
                val effect = (state: Engine.State[S]) => (error: Option[Throwable]) => {
                  directive.effect(error.toLeft(state.seqNr))
                }
                directive.change match {
                  case Some(change: Change[S, E]) =>
                    val state1 = State(
                      Engine.State(
                        value = change.state,
                        seqNr = state.value.seqNr + change.events.size),
                      stopped = directive.stop)
                    stateRef
                      .set(state1)
                      .as(EventsAndEffect(change.events.values.toList, effect(state1.value)))

                  case None =>
                    val state1 = state.copy(stopped = directive.stop)
                    stateRef
                      .set(state1)
                      .as(EventsAndEffect(List.empty, effect(state1.value)))
                }
              }
            }
          } yield result
          result.toFuture
        }
        .batch(bufferSize.toLong, a => Nel.of(a)) { (as, a) => a :: as }
        .mapAsync(1) { as =>
          val eventsAndEffects = as
            .reverse
            .toList

          eventsAndEffects
            .flatMap { _.events }
            .toNel
            .fold {
              append.error
            } { events =>
              append(Events(events)).redeem(_.some, _ => none)
            }
            .map { error => eventsAndEffects.foldMapM { _.effect(error) } }
            .toFuture
        }
        .buffer(bufferSize, OverflowStrategy.backpressure)
        .mapAsync(1) { _.toFuture }
        .to(Sink.ignore)

      ResourceFromQueue { graph.run()(materializer) }
    }

    for {
      stateRef    <- Ref[F].of(State(initial, stopped = false)).toResource
      append      <- Append.of(append).toResource
      cores       <- Runtime.summon[F].availableCores.toResource
      parallelism  = (cores max 2) * 10
      queue       <- queue(parallelism, stateRef, append)
      engine       = {
        def loadOf[A](
          load: F[Validate[F, S, E, A]],
          deferred: Deferred[F, Either[Throwable, A]]
        ): F[Validate[F, S, E, Unit]] = {
          load
            .attempt
            .flatMap {
              case Right(validate) =>
                val result = Validate[S] { (state, seqNr) =>
                  validate(state, seqNr)
                    .attempt
                    .flatMap {
                      case Right(directive) =>

                        val effect = Effect[F, Unit] { seqNr =>
                          directive
                            .effect(seqNr)
                            .attempt
                            .flatMap { a => deferred.complete(a) }
                        }

                        directive
                          .copy(effect = effect)
                          .pure[F]

                      case Left(error) =>
                        deferred
                          .complete(error.asLeft)
                          .as(Directive.empty[F, S, E])
                    }
                }
                result.pure[F]

              case Left(error) =>
                deferred
                  .complete(error.asLeft)
                  .as(Validate.empty[F, S, E])
            }
        }

        def offer(fiber: Fiber[F, Validate[F, S, E, Unit]]) = {
          FromFuture
            .summon[F]
            .apply { queue.offer(fiber.join) }
            .adaptError { case e => EngineError(s"queue offer failed: $e", e) }
            .flatMap {
              case QueueOfferResult.Enqueued    => ().pure[F]
              case QueueOfferResult.Dropped     => EngineError("queue offer dropped").raiseError[F, Unit]
              case QueueOfferResult.Failure(e)  => EngineError(s"queue offer failed: $e", e).raiseError[F, Unit]
              case QueueOfferResult.QueueClosed => EngineError("queue closed").raiseError[F, Unit]
            }
        }


        new Engine[F, S, E] {

          val state = stateRef.get.map { _.value }

          def apply[A](load: F[Validate[F, S, E, A]]) = {
            for {
              deferred <- Deferred[F, Either[Throwable, A]]
              fiber    <- loadOf(load, deferred).start
              _        <- offer(fiber)
            } yield {
              deferred.get.flatMap { _.liftTo[F] }
            }
          }
        }
      }
      engine       <- fenced(engine)
    } yield engine
  }


  def fenced[F[_]: Sync, S, E](engine: Engine[F, S, E]): Resource[F, Engine[F, S, E]] = {
    Resource
      .make {
        Ref[F].of(false)
      } { released =>
        released.set(true)
      }
      .map { released =>
        new Engine[F, S, E] {

          def state = engine.state

          def apply[A](load: F[Validate[F, S, E, A]]) = {
            for {
              released <- released.get
              _        <- if (released) Engine.released.raiseError[F, Unit] else ().pure[F]
              result   <- engine(load)
            } yield result
          }
        }
      }
  }


  final case class State[A](value: A, seqNr: SeqNr)

  object State {

    implicit val functorState: Functor[State] = new Functor[State] {
      def map[A, B](fa: State[A])(f: A => B): State[B] = fa.copy(value = f(fa.value))
    }
  }


  trait Append[F[_], -A] {

    def apply(events: Events[A]): F[SeqNr]
  }

  object Append {

    def const[F[_], A](seqNr: F[SeqNr]): Append[F, A] = _ => seqNr

    def empty[F[_]: Applicative, A]: Append[F, A] = const(SeqNr.Min.pure[F])


    def apply[F[_]: FlatMap, A](
      append: akkaeffect.persistence.Append[F, A]
    ): Append[F, A] = {
      events => append(events).flatten
    }


    def of[F[_]: Sync, A](initial: SeqNr): F[Append[F, A]] = {
      Ref[F]
        .of(initial)
        .map { seqNrRef =>
          events => {
            val size = events.size
            seqNrRef.modify { seqNr =>
              val seqNr1 = seqNr + size
              (seqNr1, seqNr1)
            }
          }
        }
    }
  }
}