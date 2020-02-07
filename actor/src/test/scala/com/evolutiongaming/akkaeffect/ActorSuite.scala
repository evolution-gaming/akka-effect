package com.evolutiongaming.akkaeffect

import akka.actor.ActorSystem
import cats.effect.IO
import com.evolutiongaming.akkaeffect.IOSuite._
import com.evolutiongaming.catshelper.CatsHelper._
import org.scalatest.{BeforeAndAfterAll, Suite}

// TODO cleanup usages
trait ActorSuite extends BeforeAndAfterAll { self: Suite =>

  lazy val (actorSystem: ActorSystem, actorSystemRelease: IO[Unit]) = {
    val actorSystem = TestActorSystem[IO](getClass.getSimpleName)
    actorSystem.allocated.toTry.get
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
