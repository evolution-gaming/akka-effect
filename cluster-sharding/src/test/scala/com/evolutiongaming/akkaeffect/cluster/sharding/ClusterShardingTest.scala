package com.evolutiongaming.akkaeffect.cluster.sharding

import akka.actor.{Actor, Props}
import akka.cluster.sharding.ClusterShardingSettings
import akka.cluster.sharding.ShardCoordinator.LeastShardAllocationStrategy
import akka.cluster.sharding.ShardRegion.Msg
import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Resource}
import cats.syntax.all.*
import com.evolutiongaming.akkaeffect.IOSuite.*
import com.evolutiongaming.akkaeffect.persistence.TypeName
import com.evolutiongaming.akkaeffect.testkit.Probe
import com.evolutiongaming.akkaeffect.{ActorRefOf, ActorSuite}
import com.evolutiongaming.catshelper.LogOf
import com.typesafe.config.ConfigFactory
import org.scalatest.funsuite.AsyncFunSuite
import org.scalatest.matchers.should.Matchers

class ClusterShardingTest extends AsyncFunSuite with ActorSuite with Matchers {

  override def config =
    IO {
      ConfigFactory
        .load("ClusterShardingTest.conf")
        .some
    }

  test("start") {
    case object HandOffStopMessage

    val result = for {
      logOf                   <- LogOf.slf4j[IO].toResource
      log                     <- logOf(classOf[ClusterShardingTest]).toResource
      clusterSharding         <- ClusterSharding.of[IO](actorSystem)
      clusterSharding         <- clusterSharding.withLogging1(log).pure[Resource[IO, *]]
      clusterShardingSettings <- IO(ClusterShardingSettings(actorSystem)).toResource
      actorRefOf               = ActorRefOf.fromActorRefFactory[IO](actorSystem)
      probe                   <- Probe.of[IO](actorRefOf)
      props                    = {
        def actor() = new Actor {
          def receive = {
            case ()                 => sender().tell((), self)
            case HandOffStopMessage => context.stop(self)
          }
        }

        Props(actor())
      }
      shardRegion <- clusterSharding.start(
        TypeName("typeName"),
        props,
        clusterShardingSettings,
        { case a => ("entityId", a) },
        (_: Msg) => "shardId",
        new LeastShardAllocationStrategy(1, 1),
        HandOffStopMessage,
      )
    } yield for {
      a <- probe.expect[Unit]
      _ <- IO(shardRegion.tell((), probe.actorEffect.toUnsafe))
      a <- a
    } yield a.msg
    result
      .use(identity)
      .run()
  }
}
