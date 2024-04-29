package com.evolutiongaming.akkaeffect.eventsourcing

import akka.actor.ActorSystem
import akka.stream._
import akka.stream.scaladsl.{Sink, Source}
import cats.data.{NonEmptyList => Nel}
import cats.effect.implicits._
import cats.effect._
import cats.syntax.all._
import cats.{Applicative, FlatMap, Functor, Monad}
import com.evolutiongaming.akkaeffect
import com.evolutiongaming.akkaeffect.eventsourcing.util.ResourceFromQueue
import com.evolutiongaming.akkaeffect.persistence.{Events, SeqNr}
import com.evolutiongaming.akkaeffect.util.CloseOnError
import com.evolutiongaming.catshelper.CatsHelper._
import com.evolutiongaming.catshelper.{FromFuture, Runtime, SerParQueue, ToFuture}


trait Engine[F[_], S, E] {
  import Engine._

  /**
    * Get effective state.
    * Effective state is latest persisted state, should be used for all async operations with [[Journaller]] or [[Snapshotter]]
    */
  def effective: F[State[S]]

  /**
    * Get optimistic state.
    * Optimistic aka speculative state is used internally to keep running incoming commands in parallel
    * with persisting events from past changes
    */
  def optimistic: F[State[S]]

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

    val effective = initial.pure[F]

    val optimistic = initial.pure[F]

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
  def of[F[_]: Async: ToFuture: FromFuture, S, E](
    initial: State[S],
    actorSystem: ActorSystem,
    append: akkaeffect.persistence.Append[F, E],
  ): Resource[F, Engine[F, S, E]] = {
    for {
      materializer <- Sync[F].delay { SystemMaterializer(actorSystem).materializer }.toResource
      engine       <- of(initial, materializer, Append(append))
    } yield engine
  }


  def of[F[_]: Async: Runtime: ToFuture: FromFuture, S, E](
    initial: State[S],
    materializer: Materializer,
    append: Append[F, E],
    bufferSize: Int = Int.MaxValue
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

    def queue(
      parallelism: Int,
      stateRef: Ref[F, State],
      effectiveRef: Ref[F, Engine.State[S]],
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
                  for {
                    _ <- effectiveRef.set(state).whenA(error.isEmpty)
                    _ <- directive.effect(error.toLeft(state.seqNr))
                  } yield {}
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
        .batch(bufferSize.toLong, a => a :: Nil) { (as, a) => a :: as }
        .mapAsync(1) { as =>
          val eventsAndEffects = as.reverse
          eventsAndEffects
            .flatMap { _.events }
            .toNel
            .fold {
              append.error
            } { events =>
              append(Events(events)).redeem(_.some, _ => none)
            }
            .map { error => Sync[F].defer { eventsAndEffects.foldMapM { _.effect(error) } } }
            .toFuture
        }
        .buffer(bufferSize, OverflowStrategy.backpressure)
        .mapAsync(1) { _.toFuture }
        .to(Sink.ignore)

      ResourceFromQueue { graph.run()(materializer) }
    }

    for {
      stateRef      <- Ref[F].of(State(initial, stopped = false)).toResource
      effectiveRef  <- Ref[F].of(initial).toResource
      append        <- Append.of(append).toResource
      cores         <- Runtime.summon[F].availableCores.toResource
      parallelism    = (cores max 2) * 10
      queue         <- queue(parallelism, stateRef, effectiveRef, append)
      engine         = {
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
                            .flatMap { a => deferred.complete(a).void }
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

        val void = ().pure[F]

        def offer(fiber: Fiber[F, Throwable, Validate[F, S, E, Unit]]) = {
          FromFuture
            .summon[F]
            .apply { queue.offer(fiber.joinWithNever) }
            .adaptError { case e => EngineError(s"queue offer failed: $e", e) }
            .flatMap {
              case QueueOfferResult.Enqueued    => void
              case QueueOfferResult.Dropped     => EngineError("queue offer dropped").raiseError[F, Unit]
              case QueueOfferResult.Failure(e)  => EngineError(s"queue offer failed: $e", e).raiseError[F, Unit]
              case QueueOfferResult.QueueClosed => EngineError("queue closed").raiseError[F, Unit]
            }
        }

        new Engine[F, S, E] {

          def state = stateRef.get.map { _.value }

          def effective: F[Engine.State[S]] = effectiveRef.get

          def optimistic: F[Engine.State[S]] = stateRef.get.map { _.value }

          def apply[A](load: F[Validate[F, S, E, A]]) = {
            for {
              d <- Deferred[F, Either[Throwable, A]]
              f <- loadOf(load, d).start
              _ <- offer(f)
            } yield for {
              a <- d.get
              a <- a.liftTo[F]
            } yield a
          }
        }
      }
      engine       <- fenced(engine)
    } yield engine
  }

  /**
   * Cats-effect based implementation, expected to be used '''only in tests'''
   *
   * Executes following stages
   * 1. Load: parallel, in case you need to call external world
   * 2. Validation: serially validates and changes current state
   * 3. Append: appends events produced in #2
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
  def of[F[_]: Async, S, E](
    initial: State[S],
    append: Append[F, E],
  ): Resource[F, Engine[F, S, E]] = {

    sealed trait Key
    object Key {
      case object validate extends Key
      case object persist  extends Key
      case object effect   extends Key
    }

    case class Wrapped(state: State[S], stopped: Boolean = false)

    val engine: F[Engine[F, S, E]] = for {
      /** Mutable variable of [[Engine.State]] wrapped together with boolean stopped var */
      ref   <- Ref.of[F, Wrapped](Wrapped(initial))
      /** Effective state representing last persisted state of [[ref]] */
      eff   <- Ref.of[F, State[S]](initial)
      /** Effect executor that guarantee sequential execution of tasks within one key */
      queue <- SerParQueue.of[F, Key]
      /** Latch that closes on error and continue raising the error on each execution */
      close <- CloseOnError.of[F]
    } yield new Engine[F, S, E] {

      override def effective: F[State[S]] = eff.get

      override def optimistic: F[State[S]] = ref.get.map(_.state)

      override def apply[A](load: F[Validate[F, S, E, A]]): F[F[A]] = {
        for {
          d  <- Deferred[F, Either[Throwable, A]]
          fv <- load.start // fork `load` stage to allow multiple independent executions
          fu =  execute(fv.joinWithNever, d)
          _  <- queue(Key.validate.some)(fu)
        } yield for {
          e <- d.get
          a <- e.liftTo[F]
        } yield a
      }

      /**
       * Execute `load` with respect to:
       *  1. failure on `load` or `validate` will be propagated to user
       *  2. stopped Engine will not persist any events or change its state
       *  3. `load` stages executed unordered and in parallel
       *  4. `validate` stages executed strictly sequentially
       *  5. `persist` happened strictly sequentially
       *  6. `effect`s executed strictly sequentially
       *
       * Please check [[EngineCatsEffectTest]] for more restrictions of the implementation
       */
      def execute[A](
        load: F[Validate[F, S, E, A]],
        reply: Deferred[F, Either[Throwable, A]]
      ): F[Unit] = {

        0.tailRecM { _ =>
          ref.access.flatMap {
            case (Wrapped(state0, stopped), update) =>

              /** await for `load` stage to complete & run `validate` stage */
              val directive =
                for {
                  validate  <- load
                  directive <- validate(state0.value, state0.seqNr)
                } yield directive

              directive.attempt.flatMap {

                /** on error reply to user & exit loop */
                case Left(error)      =>
                  for {
                    _ <- reply.complete(error.asLeft[A])
                  } yield ().asRight[Int]

                case Right(directive) =>

                  if (stopped) {
                    /** if Engine already stopped then execute side effects with [[Engine.stopped]] error */
                    val effect = {
                      for {
                        e <- close.error
                        e <- e.getOrElse(Engine.stopped).pure[F]
                        a <- directive.effect(e.asLeft[SeqNr]).attempt
                        _ <- reply.complete(a)
                      } yield {}
                    }
                    queue(Key.effect.some)(effect) as ().asRight[Int] // enqueue {{{ effect: F[Unit] }}} as `Key.effect`
                  } else directive.change match {

                    case None =>
                      /** if state not changed - execute side effects */
                      val effect = {
                        for {
                          e <- close.error
                          a <- directive.effect(e.toLeft(state0.seqNr)).attempt
                          _ <- reply.complete(a)
                        } yield {}
                      }
                      for {
                        updated <- update(Wrapped(state0, directive.stop)) // update internal state ref
                        _       <- queue(Key.effect.some)(effect)          // enqueue {{{ effect: F[Unit] }}} as `Key.effect`
                      } yield if (updated) ().asRight[Int] else 0.asLeft[Unit]

                    case Some(change) =>
                      /** if state was changed then: persist events & execute side effects  */
                      val state1 = State(change.state, state0.seqNr + change.events.size)
                      update(Wrapped(state1, directive.stop)).flatMap { updated =>
                        if (updated) {
                          // create {{{ persist: F[Unit] }}} job
                          val persist =
                            for {
                              // persist events if [[close]] allow
                              seqNr <- close { append(change.events) <* eff.set(state1) }.attempt
                              // create {{{ effect: F[Unit] }}} job
                              effect = for {
                                         a <- directive.effect(seqNr).attempt
                                         _ <- reply.complete(a)
                                       } yield {}
                              // enqueue {{{ effect: F[Unit] }}} as `Key.effect`
                              _     <- queue(Key.effect.some)(effect)
                            } yield {}
                          for {
                            // enqueue {{{ persist: F[Unit] }}} as `Key.persist`
                            _ <- queue(Key.persist.some)(persist)
                          } yield ().asRight[Int]
                        } else
                          0.asLeft[Unit].pure[F]
                      }

                  }
              }
          }
        }.uncancelable
      }
    }

    for {
      engine <- engine.toResource
      engine <- fenced(engine)
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

          def effective: F[State[S]] = engine.effective

          def optimistic: F[State[S]] = engine.optimistic

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


    def of[F[_], A](initial: SeqNr, appended: Ref[F, List[A]]): Append[F, A] =
      events =>
        appended.modify { persisted =>
          val applied = persisted ++ events.toList
          val seqNr = initial + applied.length
          applied -> seqNr
        }
  }
}