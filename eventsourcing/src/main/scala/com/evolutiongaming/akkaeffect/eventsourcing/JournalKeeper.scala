package com.evolutiongaming.akkaeffect.eventsourcing

// format: off
import akka.persistence.SnapshotSelectionCriteria
import cats.Applicative
import cats.effect.concurrent.Ref
import cats.effect.implicits._
import cats.effect.{Clock, Concurrent}
import cats.syntax.all._
import cats.kernel.Order
import com.evolutiongaming.akkaeffect.ActorStoppedError
import com.evolutiongaming.akkaeffect.persistence.{SeqNr, SnapshotMetadata, Snapshotter}
import com.evolutiongaming.catshelper.ClockHelper._
import com.evolutiongaming.catshelper.Log
import pureconfig.ConfigReader
import pureconfig.generic.semiauto.deriveReader

import scala.concurrent.duration._


trait JournalKeeper[F[_], Sn, St] {

  /**
    * Called after events are saved
    */
  def eventsSaved(seqNr: SeqNr, state: St): F[Unit]

  /**
    * @return Journaller with hooks attached in order to monitor external actions
    */
  def journaller: Journaller[F]

  /**
    * @return Snapshotter with hooks attached in order to monitor external actions
    */
  def snapshotter: Snapshotter[F, Sn]

}

object JournalKeeper {

  trait SnapshotOf[F[_], St, Sn] {

    def apply(candidate: Candidate[St]): F[Option[Sn]]
  }

  object SnapshotOf {

    def empty[F[_]: Applicative, St, Sn]: SnapshotOf[F, St, Sn] = _ => none[Sn].pure[F]

    def identity[F[_]: Applicative, S]: SnapshotOf[F, S, S] = _.value.some.pure[F]
    

    def apply[St]: Apply[St] = new Apply[St]

    private[SnapshotOf] final class Apply[St](private val b: Boolean = true) extends AnyVal {

      def apply[F[_], Sn](f: Candidate[St] => F[Option[Sn]]): SnapshotOf[F, St, Sn] = {
        candidate => f(candidate)
      }
    }
  }

  /**
    * JournalKeeper is responsible for
    * 1. taking snapshots according to config
    * 2. deleting previous snapshots
    * 3. deleting events prior snapshot
    * Does not take more than one snapshot at a time, useful for loaded entities
    *
    * @tparam Sn snapshot
    * @tparam St state
    */
  def of[F[_]: Concurrent: Clock, Sn, St](
    candidate: Candidate[St],
    snapshotOffer: Option[SnapshotMetadata],
    journaller: Journaller[F],
    snapshotter: Snapshotter[F, Sn],
    snapshotOf: SnapshotOf[F, St, Sn],
    config: Config,
    log: Log[F]
  ): F[JournalKeeper[F, Sn, St]] = {

    final case class Ctx(candidate: Candidate[St], prev: Option[SnapshotMetadata])

    sealed trait S

    object S {

      def idle(last: Option[SnapshotMetadata]): S = Idle(last)

      def saving(candidate: Option[Candidate[St]]): S = Saving(candidate)


      final case class Idle(last: Option[SnapshotMetadata]) extends S

      final case class Saving(candidate: Option[Candidate[St]]) extends S
    }


    trait Check {
      def apply(last: Option[SnapshotMetadata], now: Long, candidate: Candidate[St]): Boolean
    }

    object Check {
      def apply(recovered: Long): Check = {
        (last: Option[SnapshotMetadata], now: Long, candidate: Candidate[St]) => {

          def cooldownCheck = {
            val timestamp = last.fold(recovered) { _.timestamp.toEpochMilli }
            timestamp + config.saveSnapshotCooldown.toMillis <= now
          }

          def seqNrCheck = candidate.seqNr - last.fold(0L) { _.seqNr } >= config.saveSnapshotPerEvents

          seqNrCheck && cooldownCheck
        }
      }
    }

    val journaller0  = journaller

    val snapshotter0 = snapshotter

    def saveAndDelete(ctx: Ctx, snapshot: Sn, deletedTo: Ref[F, Option[SeqNr]]): F[SnapshotMetadata] = {

      def deleteOldSnapshots: F[Unit] = {
        if (config.deleteOldSnapshots) {
          ctx
            .prev
            .foldMapM { prev =>
              val seqNr = prev.seqNr
              snapshotter0
                .delete(seqNr)
                .flatten
                .handleErrorWith {
                  case _: ActorStoppedError => ().pure[F]
                  case e                    => log.warn(s"delete snapshot at $seqNr failed with $e", e)
                }
            }
        } else {
          ().pure[F]
        }
      }

      def deleteOldEvents: F[Unit] = {
        if (config.deleteOldEvents) {
          ctx
            .prev
            .foldMapM { prev =>
              val deleteTo = prev.seqNr
              if (deleteTo <= 1) {
                ().pure[F]
              } else {
                deletedTo
                  .modify { deletedTo =>
                    if (deletedTo.exists { _ >= deleteTo }) {
                      (deletedTo, ().pure[F])
                    } else {
                      val result = journaller0
                        .deleteTo(deleteTo)
                        .flatten
                        .handleErrorWith {
                          case _: ActorStoppedError => ().pure[F]
                          case e                    => log.warn(s"delete events to $deleteTo failed with $e", e)
                        }
                      (deleteTo.some, result)
                    }
                  }
                  .flatten
              }
            }
        } else {
          ().pure[F]
        }
      }

      for {
        a <- snapshotter0.save(ctx.candidate.seqNr, snapshot).flatten
        _ <- deleteOldSnapshots
        _ <- deleteOldEvents
      } yield {
        SnapshotMetadata(ctx.candidate.seqNr, a)
      }
    }


    trait Save {
      def apply(ref: Ref[F, S], ctx: Ctx): F[Unit]
    }

    object Save {
      def apply(check: Check, deletedTo: Ref[F, Option[SeqNr]]): Save = {
        (ref: Ref[F, S], ctx: Ctx) => {
          ctx
            .tailRecM { ctx =>
              val candidate = ctx.candidate
              snapshotOf(candidate).flatMap { snapshot =>
                snapshot
                  .fold {
                    ctx.prev.pure[F]
                  } { snapshot =>
                    saveAndDelete(ctx, snapshot, deletedTo).map { _.some }
                  }
                  .attempt
                  .flatMap {
                    case Right(metadata) =>
                      for {
                        timestamp <- Clock[F].millis
                        result    <- ref.modify {
                          case S.Saving(Some(candidate)) if check(metadata, timestamp, candidate) =>
                            (S.saving(none), Ctx(candidate, metadata).asLeft)
                          case _                                                                  =>
                            (S.idle(metadata), ().asRight[Ctx])
                        }
                      } yield result

                    case Left(e) =>
                      for {
                        _ <- e match {
                          case _: ActorStoppedError => ().pure[F]
                          case e                    => log.error(s"save snapshot at ${ candidate.seqNr } failed with $e", e)
                        }
                        a <- ref.modify {
                          case S.Saving(Some(candidate)) =>
                            (S.saving(none), ctx.copy(candidate = candidate).asLeft)
                          case _                         =>
                            (S.idle(ctx.prev), ().asRight[Ctx])
                        }
                      } yield a

                  }
              }
            }
            .start
            .void
        }
      }
    }


    for {
      deletedTo <- Ref[F].of(none[SeqNr])
      timestamp <- Clock[F].millis
      check      = Check(timestamp)
      save       = Save(check, deletedTo)
      (s, f)     = {
        if (check(snapshotOffer, timestamp, candidate)) {
          val s = S.saving(none)
          val f = save(_, Ctx(candidate, snapshotOffer))
          (s, f)
        } else {
          val f = (_: Ref[F, S]) => ().pure[F]
          (S.idle(snapshotOffer), f)
        }
      }
      ref       <- Ref[F].of(s)
      _         <- f(ref)
    } yield {
      new JournalKeeper[F, Sn, St] {

        def eventsSaved(seqNr: SeqNr, state: St) = {
          val candidate = Candidate(seqNr, state)
          for {
            timestamp <- Clock[F].millis
            result    <- ref.modify {
              case s: S.Idle   =>
                val meta = s.last
                if (check(meta, timestamp, candidate)) {
                  (S.saving(none), save(ref, Ctx(candidate, meta)))
                } else {
                  (s, ().pure[F])
                }
              case s: S.Saving =>
                val s1 = if (s.candidate.forall { _ <= candidate }) s.copy(candidate = candidate.some) else s
                (s1, ().pure[F])
            }
            result    <- result
          } yield result
        }

        val journaller = {
          (seqNr: SeqNr) => {
            journaller0
              .deleteTo(seqNr)
              .flatMap { result =>
                result
                  .productL { deletedTo.update {  _.fold(seqNr.some) { _.max(seqNr).some } } }
                  .start
                  .map { _.join }
              }
          }
        }

        val snapshotter = new Snapshotter[F, Sn] {

          def save(seqNr: SeqNr, snapshot: Sn) = {
            snapshotter0
              .save(seqNr, snapshot)
              .flatMap { result =>
                result
                  .flatTap { timestamp =>
                    ref.update {
                      case a: S.Idle if a.last.forall { _.seqNr < seqNr } =>
                        S.Idle(SnapshotMetadata(seqNr, timestamp).some)
                      case a                                              =>
                        a
                    }
                  }
                  .start
                  .map { _.join }
              }
          }

          def delete(seqNr: SeqNr) = {
            snapshotter0
              .delete(seqNr)
              .flatMap { result =>
                result
                  .productL {
                    ref.update {
                      case a: S.Idle if a.last.exists { _.seqNr == seqNr } => S.Idle(none)
                      case a                                               => a
                    }
                  }
                  .start
                  .map { _.join }
              }
          }

          def delete(criteria: SnapshotSelectionCriteria) = {

            def selected(meta: SnapshotMetadata) = {
              meta.seqNr <= criteria.maxSequenceNr && meta.timestamp.toEpochMilli <= criteria.maxSequenceNr
            }

            snapshotter0
              .delete(criteria)
              .flatMap { result =>
                result
                  .productL {
                    ref.update {
                      case a: S.Idle if a.last.exists(selected) => S.Idle(none)
                      case a                                    => a
                    }
                  }
                  .start
                  .map { _.join }
              }
          }
        }
      }
    }
  }


  final case class Candidate[+A](seqNr: SeqNr, value: A)

  object Candidate {
    implicit def ordered[A]: Order[Candidate[A]] = Order.by { a: Candidate[A] => a.seqNr }
  }


  final case class Config(
    saveSnapshotPerEvents: Int = 100,
    saveSnapshotCooldown: FiniteDuration = 1.minute,
    deleteOldSnapshots: Boolean = true,
    deleteOldEvents: Boolean = false)

  object Config {

    val Default: Config = Config()

    implicit val configReaderConfig: ConfigReader[Config] = deriveReader
  }
}
