package akka.persistence

import akka.actor.{ActorContext, ActorRef}
import akka.persistence.JournalProtocol.DeleteMessagesTo
import cats.effect.{Resource, Sync}
import cats.implicits._
import com.evolutiongaming.akkaeffect.persistence.{DeleteEventsTo, SeqNr}
import com.evolutiongaming.akkaeffect.{ActorRefOf, AskFrom}
import com.evolutiongaming.catshelper.FromFuture

import scala.concurrent.duration.FiniteDuration

object DeleteEventsToInterop {

  def apply[F[_] : Sync : FromFuture](
    eventsourced: Eventsourced,
    timeout: FiniteDuration
  ): Resource[F, DeleteEventsTo[F]] = {
    apply(Interop(eventsourced), timeout)
  }

  private[persistence] def apply[F[_] : Sync : FromFuture](
    eventsourced: Interop,
    timeout: FiniteDuration
  ): Resource[F, DeleteEventsTo[F]] = {

    val actorRefOf = ActorRefOf.fromActorRefFactory[F](eventsourced.context)

    AskFrom
      .of[F](actorRefOf, eventsourced.self, timeout)
      .map { askFrom =>

        def persistenceId = eventsourced.persistenceId

        def journal = eventsourced.journal

        (seqNr: SeqNr) => {
          askFrom[DeleteMessagesTo, Any](journal) { from => DeleteMessagesTo(persistenceId, seqNr, from) }
            .map { result =>
              result.flatMap {
                case _: DeleteMessagesSuccess => ().pure[F]
                case a: DeleteMessagesFailure => a.cause.raiseError[F, Unit]
              }
            }
        }
      }
  }


  trait Interop {

    def persistenceId: String

    def context: ActorContext

    def self: ActorRef

    def journal: ActorRef
  }

  object Interop {

    def apply(eventsourced: Eventsourced): Interop = new Interop {

      def persistenceId = eventsourced.persistenceId

      def context = eventsourced.context

      def self = eventsourced.self

      def journal = eventsourced.journal
    }
  }
}