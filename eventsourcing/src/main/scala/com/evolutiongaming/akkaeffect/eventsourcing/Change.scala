package com.evolutiongaming.akkaeffect.eventsourcing

import cats.data.{NonEmptyList => Nel}

final case class Change[+S, +E](state: S, events: Nel[Nel[E]])

