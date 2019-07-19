package com.evolutiongaming.akkaeffect

import akka.actor.ActorSystem
import cats.effect.{Resource, Sync}
import cats.implicits._
import com.evolutiongaming.akkaeffect.IOSuite.executor
import com.evolutiongaming.catshelper.FromFuture
import com.typesafe.config.{Config, ConfigFactory}

object TestActorSystem {

  def apply[F[_] : Sync : FromFuture](name: String): Resource[F, ActorSystem] = {

    val config = Sync[F].delay { ConfigFactory.load("test.conf") }

    def actorSystemOf(config: Config) = {
      Resource.make {
        Sync[F].delay {
          ActorSystem(
            name = name,
            config = config.some,
            defaultExecutionContext = executor.some)
        }
      } { actorSystem =>
        FromFuture[F].apply { actorSystem.terminate() }.void
      }
    }

    for {
      config      <- Resource.liftF(config)
      actorSystem <- actorSystemOf(config)
    } yield actorSystem
  }
}
