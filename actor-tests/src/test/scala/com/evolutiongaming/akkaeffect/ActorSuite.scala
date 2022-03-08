package com.evolutiongaming.akkaeffect

import akka.actor.ActorSystem
import cats.effect.IO
import cats.effect.implicits.effectResourceOps
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import com.evolutiongaming.akkaeffect.testkit.TestActorSystem
import com.evolutiongaming.catshelper.CatsHelper._
import com.typesafe.config.Config
import org.scalatest.{BeforeAndAfterAll, Suite}

trait ActorSuite extends BeforeAndAfterAll { self: Suite =>

  def config: IO[Option[Config]] = none[Config].pure[IO]

  lazy val (actorSystem: ActorSystem, actorSystemRelease: IO[Unit]) = {
    val result = for {
      config      <- config.toResource
      actorSystem <- TestActorSystem[IO](getClass.getSimpleName, config)
    } yield actorSystem
    result
      .allocated
      .toTry
      .get
  }

  override def beforeAll(): Unit = {
    super.beforeAll()
    actorSystemRelease
    ()
  }

  override def afterAll(): Unit = {
    actorSystemRelease.toTry.get
    super.afterAll()
  }
}
