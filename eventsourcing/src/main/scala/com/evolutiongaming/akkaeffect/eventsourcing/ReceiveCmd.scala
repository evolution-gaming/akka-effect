package com.evolutiongaming.akkaeffect.eventsourcing

trait ReceiveCmd[F[_], S, C, E] {

  def apply(cmd: C): F[Validate[F, S, E]]
}