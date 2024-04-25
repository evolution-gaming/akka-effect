package com.evolutiongaming.akkaeffect.cluster

import cats.{Order, Show}
import pureconfig.ConfigReader


final case class Role(value: String) {

  override def toString: String = value
}

object Role {

  implicit val orderRole: Order[Role] = Order.by { (a: Role) => a.value }

  implicit val showRole: Show[Role] = Show.fromToString

  implicit val configReaderRole: ConfigReader[Role] = ConfigReader[String].map { a => Role(a) }
}