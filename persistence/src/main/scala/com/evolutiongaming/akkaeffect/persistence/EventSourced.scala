package com.evolutiongaming.akkaeffect.persistence

import akka.persistence.Recovery
import cats.implicits._
import cats.{Functor, Show}

/**
  * @param eventSourcedId @see [[akka.persistence.PersistentActor.persistenceId]]
  * @param recovery       @see [[akka.persistence.PersistentActor.recovery]]
  * @param pluginIds      @see [[akka.persistence.PersistentActor.journalPluginId]]
  * @param value          usually something used to construct instance of an actor
  */
final case class EventSourced[A](
  eventSourcedId: EventSourcedId,
  recovery: Recovery = Recovery(),
  pluginIds: PluginIds = PluginIds.Empty,
  value: A)

object EventSourced {

  implicit val functorEventSourced: Functor[EventSourced] = new Functor[EventSourced] {
    def map[A, B](fa: EventSourced[A])(f: A => B) = fa.map(f)
  }

  implicit def showEventSourced[A: Show]: Show[EventSourced[A]] = {
    a => show"${ a.productPrefix }(${ a.eventSourcedId },${ a.pluginIds },${ a.recovery },${ a.value })"
  }

  implicit class EventSourcedOps[A](val self: EventSourced[A]) extends AnyVal {
    def map[B](f: A => B): EventSourced[B] = self.copy(value = f(self.value))
  }
}