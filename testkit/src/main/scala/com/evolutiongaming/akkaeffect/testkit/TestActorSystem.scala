package com.evolutiongaming.akkaeffect.testkit

import akka.actor.ActorSystem
import cats.effect.{Async, Resource, Sync}
import cats.implicits._
import com.evolutiongaming.catshelper.CatsHelper._
import com.evolutiongaming.catshelper.FromFuture
import com.typesafe.config.{Config, ConfigFactory}

import scala.concurrent.ExecutionContext

object TestActorSystem {

  def apply[F[_]: Async](name: String): Resource[F, ActorSystem] = {
    for {
      config      <- Sync[F].delay { ConfigFactory.load("test.conf") }.toResource
      actorSystem <- apply(name, config)
    } yield actorSystem
  }

  def apply[F[_]: Async](name: String, config: Config): Resource[F, ActorSystem] = {

    def actorSystem(implicit executor: ExecutionContext) = {
      Resource.make {
        Sync[F].delay {
          ActorSystem(
            name = name,
            config = config.some,
            defaultExecutionContext = executor.some)
        }
      } { actorSystem =>
        FromFuture.summon[F].apply { actorSystem.terminate() }.void
      }
    }

    for {
      executor    <- Sync[F].delay { ExecutionContext.global }.toResource
      actorSystem <- actorSystem(executor)
    } yield actorSystem
  }
}
