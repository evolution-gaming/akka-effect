package com.evolutiongaming.akkaeffect.persistence

import akka.actor.ExtendedActorSystem
import cats.effect.implicits.effectResourceOps
import cats.syntax.all._
import cats.effect.{Async, IO, Resource}
import cats.effect.unsafe.implicits.global
import com.evolutiongaming.akkaeffect.testkit.TestActorSystem
import com.evolutiongaming.catshelper.ToTry
import com.evolutiongaming.catshelper.CatsHelper._
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class EventSourcedStoreOfTest extends AnyFunSuite with Matchers {

  type F[A] = IO[A]
  val F = Async[F]

  implicit val toTry = ToTry.ioToTry(global)

  type S = String
  type E = String

  test("""EventSourcedStoreOf.fromAkka can use journal & snapshot plugins 
      |defined in Akka conf (in our case `test.conf`)
      |without specifying plugin IDs in EventSourced.pluginIds""".stripMargin) {

    val id = EventSourcedId("id #1")
    val es = EventSourced[Unit](id, value = {})

    val resource = for {
      as <- TestActorSystem[F]("testing", none)
      as <- as.castM[Resource[F, *], ExtendedActorSystem]
      of <- EventSourcedStoreOf.fromAkka[F, S, E](as).toResource

      store <- of(es)

      recovery0 <- store.recover(id)
      _ = recovery0.snapshot shouldEqual none

      // TODO: finish me!

    } yield {}

    resource.use_.unsafeRunSync()
  }

}
