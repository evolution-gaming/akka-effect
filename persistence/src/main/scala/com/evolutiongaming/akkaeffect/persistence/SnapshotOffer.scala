package com.evolutiongaming.akkaeffect.persistence

import akka.persistence.SnapshotMetadata
import cats.Functor

/**
  * Typesafe clone of [[akka.persistence.SnapshotOffer]]
  */
final case class SnapshotOffer[+A](metadata: SnapshotMetadata, snapshot: A)

object SnapshotOffer {

  implicit val functorSnapshotOffer: Functor[SnapshotOffer] = new Functor[SnapshotOffer] {
    def map[A, B](fa: SnapshotOffer[A])(f: A => B) = fa.map(f)
  }


  implicit class SnapshotOfferOps[A](val self: SnapshotOffer[A]) extends AnyVal {
    def map[B](f: A => B): SnapshotOffer[B] = self.copy(snapshot = f(self.snapshot))
  }
}