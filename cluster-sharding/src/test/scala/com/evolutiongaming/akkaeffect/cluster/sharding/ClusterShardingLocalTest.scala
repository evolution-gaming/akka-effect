package com.evolutiongaming.akkaeffect.cluster.sharding


import akka.actor.{Actor, ActorRef, Props}
import akka.cluster.sharding.ShardCoordinator.LeastShardAllocationStrategy
import akka.cluster.sharding.{ClusterShardingSettings, ShardRegion}
import cats.effect.IO
import com.evolutiongaming.akkaeffect.IOSuite._
import com.evolutiongaming.akkaeffect.persistence.TypeName
import com.evolutiongaming.akkaeffect.testkit.Probe
import com.evolutiongaming.akkaeffect.{ActorEffect, ActorRefOf, ActorSuite}
import com.evolutiongaming.catshelper.CatsHelper._
import org.scalatest.funsuite.AsyncFunSuite
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._


class ClusterShardingLocalTest extends AsyncFunSuite with ActorSuite with Matchers {

  test("clusterShardingLocal") {

    case object HandOffStopMsg

    case class ShardedMsg(id: String, msg: Int)

    val actorRefOf = ActorRefOf.fromActorRefFactory(actorSystem)

    val extractEntityId: ShardRegion.ExtractEntityId = {
      case ShardedMsg(entityId, msg) => (entityId, msg)
    }

    def uniform(numberOfShards: Int): ShardRegion.ExtractShardId = {

      def shardId(entityId: ShardRegion.EntityId): ShardRegion.ShardId =
        math.abs(entityId.hashCode % numberOfShards).toString

      {
        case ShardedMsg(entityId, _)           => shardId(entityId)
        case ShardRegion.StartEntity(entityId) => shardId(entityId)
      }
    }

    val result = for {
      probe                   <- Probe.of(actorRefOf)
      actor                    = () => new Actor {
        def receive = {
          case msg =>
            probe
              .actorEffect
              .toUnsafe
              .tell(msg, sender())
        }
      }
      props                    = Props(actor())
      clusterShardingLocal    <- ClusterShardingLocal.of[IO](actorSystem)
      clusterShardingSettings <- IO { ClusterShardingSettings(actorSystem) }.toResource
      actorRef                <- clusterShardingLocal.clusterSharding.start(
        TypeName("typeName"),
        props,
        clusterShardingSettings,
        extractEntityId,
        uniform(2),
        new LeastShardAllocationStrategy(1, 1),
        HandOffStopMsg)

      actorEffect              = ActorEffect.fromActor[IO](actorRef)
    } yield for {
      a <- probe.expect[Int]
      b <- actorEffect.ask(ShardedMsg("id", 0), 1.second)
      a <- a
      _ <- IO { a.msg shouldEqual 0 }
      _ <- IO { a.from.tell(a.msg.toString, ActorRef.noSender) }
      b <- b
      _ <- IO { b shouldEqual "0" }
      a <- probe.expect[HandOffStopMsg.type]
      _ <- clusterShardingLocal.rebalance
      a <- a
      _ <- IO { a.msg shouldEqual HandOffStopMsg }
      l <- clusterShardingLocal.clusterSharding.localShards
      _ <- IO { l shouldEqual List("1") }
    } yield {}

    result
      .use { identity }
      .run()
  }
}
