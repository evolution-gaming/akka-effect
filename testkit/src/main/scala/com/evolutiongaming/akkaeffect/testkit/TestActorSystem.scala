package com.evolutiongaming.akkaeffect.testkit

import akka.actor.ActorSystem
import cats.effect.implicits.effectResourceOps
import cats.effect.{Async, Resource, Sync}
import cats.syntax.all._
import com.evolutiongaming.catshelper.FromFuture
import com.typesafe.config.{Config, ConfigFactory}

import scala.concurrent.ExecutionContext

object TestActorSystem {

  def apply[F[_]: Async](name: String, config: Option[Config]): Resource[F, ActorSystem] = {
    for {
      config      <- config match {
        case Some(a) => a.pure[Resource[F, *]]
        case None    => Sync[F].delay { ConfigFactory.load("test.conf") }.toResource
      }
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
        FromFuture[F]
          .apply { actorSystem.terminate() }
          .void
      }
    }

    for {
      executor <- Sync[F].delay { ExecutionContext.global }.toResource
      actorSystem <- actorSystem(executor)
    } yield actorSystem
  }
}
