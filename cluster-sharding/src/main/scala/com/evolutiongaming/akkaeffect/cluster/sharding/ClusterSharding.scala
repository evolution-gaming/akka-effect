package com.evolutiongaming.akkaeffect.cluster.sharding

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.cluster.sharding.ShardCoordinator.ShardAllocationStrategy
import akka.cluster.sharding.ShardRegion._
import akka.cluster.sharding.{ClusterShardingSettings, ShardRegion}
import cats.Parallel
import cats.effect.implicits._
import cats.effect.{Concurrent, Resource, Sync, Timer}
import cats.syntax.all._
import com.evolutiongaming.akkaeffect.cluster.{DataCenter, Role}
import com.evolutiongaming.akkaeffect.persistence.TypeName
import com.evolutiongaming.akkaeffect.util.Terminated
import com.evolutiongaming.akkaeffect.{ActorRefOf, Ask}
import com.evolutiongaming.catshelper.Blocking.implicits._
import com.evolutiongaming.catshelper.CatsHelper._
import com.evolutiongaming.catshelper._
import com.evolutiongaming.smetrics.MeasureDuration

import scala.concurrent.duration._

trait ClusterSharding[F[_]] {

  /**
    * @see [[akka.cluster.sharding.ClusterSharding.start]]
    */
  def start[A](
    typeName: TypeName,
    props: Props,
    settings: ClusterShardingSettings,
    extractEntityId: ShardRegion.ExtractEntityId,
    extractShardId: ShardRegion.ExtractShardId,
    allocationStrategy: ShardAllocationStrategy,
    handOffStopMessage: A
  ): Resource[F, ActorRef]


  /**
    * @see [[akka.cluster.sharding.ClusterSharding.startProxy]]
    */
  def startProxy(
    typeName: TypeName,
    role: Option[Role],
    dataCenter: Option[DataCenter],
    extractEntityId: ShardRegion.ExtractEntityId,
    extractShardId: ShardRegion.ExtractShardId
  ): Resource[F, ActorRef]

  /**
   * Return IDs of local shards. Local shard is one assigned to locally managed shard region.
   * Carefully: the implementation based on sending messages to all local shard regions and could be slow.
   */
  def localShards: F[List[String]]
}

object ClusterSharding {

  def of[F[_]: Concurrent: Parallel: Blocking: Timer: ToFuture: FromFuture](actorSystem: ActorSystem): Resource[F, ClusterSharding[F]] = {

    val actorRefOf = ActorRefOf.fromActorRefFactory(actorSystem)
    val terminated = Terminated(actorRefOf)

    def shardRegion(actorRef: => ActorRef) = {
      Resource.make {
        Sync[F]
          .delay { actorRef }
          .blocking
      } { actorRef =>
        for {
          _ <- Sync[F].delay { actorRef.tell(GracefulShutdown, ActorRef.noSender) }
          _ <- terminated(actorRef).timeout(1.minute)
        } yield {}
      }
    }

    for {
      clusterSharding <- Sync[F].delay { akka.cluster.sharding.ClusterSharding(actorSystem) }.toResource
    } yield {
      new ClusterSharding[F] {

        def start[A](
          typeName: TypeName,
          props: Props,
          settings: ClusterShardingSettings,
          extractEntityId: ShardRegion.ExtractEntityId,
          extractShardId: ShardRegion.ExtractShardId,
          allocationStrategy: ShardAllocationStrategy,
          handOffStopMessage: A
        ) = {

          shardRegion {
            clusterSharding.start(
              typeName = typeName.value,
              entityProps = props,
              settings = settings,
              extractEntityId = extractEntityId,
              extractShardId = extractShardId,
              allocationStrategy = allocationStrategy,
              handOffStopMessage = handOffStopMessage)
          }
        }

        def startProxy(
          typeName: TypeName,
          role: Option[Role],
          dataCenter: Option[DataCenter],
          extractEntityId: ShardRegion.ExtractEntityId,
          extractShardId: ShardRegion.ExtractShardId
        ) = {
          shardRegion {
            clusterSharding.startProxy(
              typeName = typeName.value,
              role = role.map { _.value },
              dataCenter = dataCenter.map { _.value },
              extractEntityId = extractEntityId,
              extractShardId = extractShardId)
          }
        }

        def localShards: F[List[ShardId]] =
          clusterSharding.shardTypeNames.toList.parFlatTraverse { typeName =>
            val ref = clusterSharding.shardRegion(typeName)
            val ask = Ask.fromActorRef[F](ref)
            for {
              send <- ask(GetShardRegionState, 30.seconds)
              resp <- send
            } yield resp match {
              case CurrentShardRegionState(shards) => shards.toList.map(_.shardId)
            }
          }

      }
    }
  }


  implicit class ClusterShardingOps[F[_]](val self: ClusterSharding[F]) extends AnyVal {

    def withLogging(implicit
      F: BracketThrowable[F],
      measureDuration: MeasureDuration[F],
      logOf: LogOf[F]
    ): F[ClusterSharding[F]] = {
      logOf(ClusterSharding.getClass).map { log => withLogging(log) }
    }

    def withLogging(
      log: Log[F])(implicit
      F: BracketThrowable[F],
      measureDuration: MeasureDuration[F]
    ): ClusterSharding[F] = {

      def measure[A](
        allocate: FiniteDuration => String,
        release: FiniteDuration => String,
        resource: Resource[F, A]
      ): Resource[F, A] = {
        val result = for {
          d <- MeasureDuration[F].start
          a <- resource.allocated
          d <- d
          _ <- log.info(allocate(d))
        } yield {
          val (a1, r) = a
          val r1 = for {
            d <- MeasureDuration[F].start
            a <- r
            d <- d
            _ <- log.info(release(d))
          } yield a
          (a1, r1)
        }
        Resource(result)
      }

      new ClusterSharding[F] {

        def start[A](
          typeName: TypeName,
          props: Props,
          settings: ClusterShardingSettings,
          extractEntityId: ShardRegion.ExtractEntityId,
          extractShardId: ShardRegion.ExtractShardId,
          allocationStrategy: ShardAllocationStrategy,
          handOffStopMessage: A
        ) = {

          measure(
            d => s"$typeName in ${ d.toMillis }ms, role: ${ settings.role }",
            d => s"$typeName release in ${ d.toMillis }ms, role: ${ settings.role }",
            self.start(
              typeName,
              props,
              settings,
              extractEntityId,
              extractShardId,
              allocationStrategy,
              handOffStopMessage))
        }

        def startProxy(
          typeName: TypeName,
          role: Option[Role],
          dataCenter: Option[DataCenter],
          extractEntityId: ShardRegion.ExtractEntityId,
          extractShardId: ShardRegion.ExtractShardId
        ) = {
          measure(
            d => s"$typeName proxy in ${ d.toMillis }ms, role: $role, dataCenter: $dataCenter",
            d => s"$typeName proxy release in ${ d.toMillis }ms, role: $role, dataCenter: $dataCenter",
            self.startProxy(typeName, role, dataCenter, extractEntityId, extractShardId))
        }

        def localShards: F[List[String]] =
          for {
            d <- MeasureDuration[F].start
            r <- self.localShards
            d <- d
            _ <- log.info(s"get local shards in ${ d.toMillis }ms")
          } yield r
      }
    }
  }
}

