package com.evolutiongaming.akkaeffect.cluster.sharding

import akka.actor.{Actor, ActorRef, Props}
import akka.cluster.sharding.ShardCoordinator.ShardAllocationStrategy
import akka.cluster.sharding.ShardRegion.{ShardId, ShardState}
import akka.cluster.sharding.{ClusterShardingSettings, ShardRegion}
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.evolutiongaming.akkaeffect.IOSuite.*
import com.evolutiongaming.akkaeffect.persistence.TypeName
import com.evolutiongaming.akkaeffect.testkit.Probe
import com.evolutiongaming.akkaeffect.{ActorEffect, ActorRefOf, ActorSuite}
import org.scalatest.funsuite.AsyncFunSuite
import org.scalatest.matchers.should.Matchers

import scala.collection.immutable.IndexedSeq
import scala.concurrent.Future
import scala.concurrent.duration.*

class ClusterShardingLocalTest extends AsyncFunSuite with ActorSuite with Matchers {

  private case class ShardedMsg(id: String, msg: Int)

  test("clusterShardingLocal") {

    case object HandOffStopMsg

    val actorRefOf = ActorRefOf.fromActorRefFactory[IO](actorSystem)

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

    // Allocation strategy that doesn't use ActorSystem and Cluster extension (as opposed to Akka built-in strategies)
    val noopAllocationStrategy = new ShardAllocationStrategy {
      override def allocateShard(
        requester: ActorRef,
        shardId: ShardId,
        currentShardAllocations: Map[ActorRef, IndexedSeq[ShardId]],
      ): Future[ActorRef] =
        Future.successful(requester)

      override def rebalance(
        currentShardAllocations: Map[ActorRef, IndexedSeq[ShardId]],
        rebalanceInProgress: Set[ShardId],
      ): Future[Set[ShardId]] =
        Future.successful(Set.empty)
    }

    val result = for {
      probe <- Probe.of[IO](actorRefOf)
      actor  = () =>
        new Actor {
          def receive = {
            case msg =>
              probe.actorEffect.toUnsafe
                .tell(msg, sender())
          }
        }
      props                    = Props(actor())
      typeName                 = TypeName("typeName")
      clusterShardingLocal    <- ClusterShardingLocal.of[IO](actorSystem)
      clusterShardingSettings <- IO(ClusterShardingSettings(actorSystem)).toResource
      actorRef                <- clusterShardingLocal.clusterSharding.start(
        typeName,
        props,
        clusterShardingSettings,
        extractEntityId,
        uniform(2),
        noopAllocationStrategy,
        HandOffStopMsg,
      )

      actorEffect = ActorEffect.fromActor[IO](actorRef)
    } yield
      for {
        a <- probe.expect[Int]
        b <- actorEffect.ask(ShardedMsg("id", 0), 1.second)
        a <- a
        _ <- IO(a.msg shouldEqual 0)
        _ <- IO(a.from.tell(a.msg.toString, ActorRef.noSender))
        b <- b
        _ <- IO(b shouldEqual "0")
        a <- probe.expect[HandOffStopMsg.type]
        _ <- clusterShardingLocal.rebalance
        a <- a
        _ <- IO(a.msg shouldEqual HandOffStopMsg)
        r <- clusterShardingLocal.clusterSharding.regions
        _ <- IO(r shouldEqual Set(typeName))
        s <- clusterShardingLocal.clusterSharding.shards(r.head)
        _ <- IO(s shouldEqual Set(ShardState("1", Set.empty)))
        r <- clusterShardingLocal.clusterSharding.shardRegion(typeName)
        _ <- IO(r shouldEqual actorRef)
      } yield {}

    result
      .use(identity)
      .run()
  }
}
