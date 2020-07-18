package com.evolutiongaming.akkaeffect.cluster

import cats.implicits._
import cats.{Order, Show}
import pureconfig.ConfigReader


final case class DataCenter(value: String) {

  override def toString: String = value
}

object DataCenter {

  implicit val orderDataCenter: Order[DataCenter] = Order.by { a: DataCenter => a.value }

  implicit val showDataCenter: Show[DataCenter] = Show.fromToString

  implicit val configReaderDataCenter: ConfigReader[DataCenter] = ConfigReader[String].map { a => DataCenter(a) }
}