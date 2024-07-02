package com.evolutiongaming.akkaeffect.cluster.sharding

import akka.actor.*
import akka.cluster.sharding.ShardCoordinator.ShardAllocationStrategy
import akka.cluster.sharding.ShardRegion.{ShardId, ShardState}
import akka.cluster.sharding.{ClusterShardingSettings, ShardRegion}
import cats.effect.syntax.resource.*
import cats.effect.{Async, Ref, Resource}
import cats.syntax.all.*
import com.evolutiongaming.akkaeffect.cluster.{DataCenter, Role}
import com.evolutiongaming.akkaeffect.persistence.TypeName
import com.evolutiongaming.akkaeffect.util.Terminated
import com.evolutiongaming.akkaeffect.{ActorRefOf, Ask}
import com.evolutiongaming.catshelper.CatsHelper.*
import com.evolutiongaming.catshelper.{FromFuture, ToFuture, ToTry}

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContextExecutor, Promise}
import scala.util.Try

/** Stub for [[ClusterSharding]] to be used in the unit tests. */
trait ClusterShardingLocal[F[_]] {

  /** Provides the actual stub */
  def clusterSharding: ClusterSharding[F]

  /** Simulate cluster rebalancing.
    *
    * I.e. send `handOffStopMessage` from [[ClusterSharding#startProxy]] to the actors that need rebalancing according
    * to `shardAllocationStrategy`.
    */
  def rebalance: F[Unit]

}

object ClusterShardingLocal {

  def of[F[_]: Async: ToFuture: FromFuture: ToTry](
    actorSystem: ActorSystem,
  ): Resource[F, ClusterShardingLocal[F]] = {

    def actorNameOf(a: String) = URLEncoder.encode(a, StandardCharsets.UTF_8.name())

    case class ShardingMsg(f: ActorContext => Unit)

    sealed trait RegionMsg

    object RegionMsg {

      final case object Rebalance extends RegionMsg
      final case object State     extends RegionMsg
    }

    def shardingActor(): Actor = new Actor {

      def receive = {
        case ShardingMsg(f) => f(context)
      }
    }

    val actorRefOf = ActorRefOf.fromActorRefFactory[F](actorSystem)
    val terminated = Terminated(actorRefOf)

    for {
      shardingRef <- actorRefOf(Props(shardingActor()))
      regionRefs  <- Ref[F].of(Map.empty[TypeName, ActorRef]).toResource
    } yield {
      def withActorContext[A](f: ActorContext => A) =
        FromFuture[F].apply {
          val promise = Promise[A]()

          def f1(context: ActorContext): Unit = {
            val result = Try(f(context))
            promise.complete(result)
          }

          shardingRef.tell(ShardingMsg(f1), ActorRef.noSender)
          promise.future
        }

      def actorOf(name: String, props: => Props) =
        Resource.make {
          withActorContext { actorContext =>
            actorContext
              .child(name)
              .getOrElse(actorContext.actorOf(props, name))
          }
        } { actorRef =>
          for {
            _ <- withActorContext(_.stop(actorRef))
            _ <- terminated(actorRef)
          } yield {}
        }

      new ClusterShardingLocal[F] {

        val clusterSharding = new ClusterSharding[F] {

          def start[A](
            typeName: TypeName,
            props: Props,
            settings: ClusterShardingSettings,
            extractEntityId: ShardRegion.ExtractEntityId,
            extractShardId: ShardRegion.ExtractShardId,
            allocationStrategy: ShardAllocationStrategy,
            handOffStopMessage: A,
          ) = {

            def shardActor(): Actor = new Actor {

              def receive = {
                case a: ShardRegion.Passivate =>
                  sender().tell(a.stopMessage, self)

                case a: ShardRegion.Msg if extractEntityId.isDefinedAt(a) =>
                  val (entityId, msg) = extractEntityId(a)
                  val entityName      = actorNameOf(entityId)
                  context
                    .child(entityId)
                    .getOrElse(context.watch(context.actorOf(props, entityName)))
                    .forward(msg)

                case _: akka.actor.Terminated =>
                  // stop itself when all children are stopped
                  if (context.children.isEmpty) {
                    context.stop(self)
                  }
              }
            }

            def regionActor(): Actor = new Actor {

              implicit private val executor: ExecutionContextExecutor = context.dispatcher

              def allocation(): Map[ActorRef, Vector[ShardId]] = {
                val shardIds = context.children.toList.map(_.path.name).toVector
                Map((self, shardIds))
              }

              case class Stashed(msg: ShardRegion.Msg, sender: ActorRef)

              var rebalancing = false
              var stashed     = Vector.empty[Stashed]

              object Unstash

              def receive: Receive = {

                case RegionMsg.Rebalance =>
                  rebalancing = true
                  context.system.scheduler.scheduleOnce(1.second, self, Unstash)
                  allocationStrategy
                    .rebalance(allocation(), Set.empty)
                    .onComplete { _ =>
                      context
                        .actorSelection("*/*")
                        .tell(handOffStopMessage, self)
                    }

                case RegionMsg.State =>
                  val shards = allocation().values.flatten.toList
                  context.sender().tell(shards, context.self)

                case Unstash =>
                  rebalancing = false
                  stashed.foreach {
                    case Stashed(msg, sender) => self.tell(msg, sender)
                  }
                  stashed = Vector.empty

                case msg: ShardRegion.Msg if rebalancing =>
                  stashed = Stashed(msg, sender()) +: stashed

                case msg: ShardRegion.Msg =>
                  val sender    = context.sender()
                  val shardId   = extractShardId(msg)
                  val shardName = actorNameOf(shardId)

                  context
                    .child(shardName)
                    .fold {
                      val shard  = context.actorOf(Props(shardActor()), shardName)
                      val future = allocationStrategy.allocateShard(shard, shardId, allocation())

                      FromFuture[F]
                        .apply(future)
                        .toTry
                        .as(shard)
                    } { shard =>
                      shard.pure[Try]
                    }
                    .fold(error => sender.tell(Status.Failure(error), sender), shard => shard.tell(msg, sender))
              }
            }

            val regionName = actorNameOf(typeName.value)
            for {
              regionRef <- actorOf(regionName, Props(regionActor()))
              _         <- regionRefs.update(_ + { typeName -> regionRef }).toResource
            } yield regionRef
          }

          def startProxy(
            typeName: TypeName,
            role: Option[Role],
            dataCenter: Option[DataCenter],
            extractEntityId: ShardRegion.ExtractEntityId,
            extractShardId: ShardRegion.ExtractShardId,
          ) = {

            val regionName = actorNameOf(typeName.value)

            def regionProxyActor(): Actor = new Actor {

              def receive: Receive = {
                case _: RegionMsg =>

                case msg: ShardRegion.Msg =>
                  val shardId   = extractShardId(msg)
                  val shardName = actorNameOf(shardId)
                  context
                    .actorSelection(s"../$regionName/$shardName")
                    .forward(msg)
              }
            }

            val regionProxyName = s"${regionName}Proxy"
            for {
              regionRef <- actorOf(regionProxyName, Props(regionProxyActor()))
              _         <- regionRefs.update(_ + { typeName -> regionRef }).toResource
            } yield regionRef
          }

          def regions: F[Set[TypeName]] =
            withActorContext { ref =>
              ref.children
                .map(_.path.name)
                .filterNot(_.endsWith("Proxy"))
                .map(TypeName(_))
                .toSet
            }

          def shards(typeName: TypeName): F[Set[ShardState]] =
            for {
              region <- withActorContext(ref => ref.children.find(_.path.name == typeName.value))
              shards <- region.toList.traverse { region =>
                val ask = Ask.fromActorRef[F](region)
                for {
                  r <- ask(RegionMsg.State, 1.second).flatten
                  s <- r.castM[F, List[ShardId]]
                } yield s.map(ShardState(_, Set.empty)).toSet
              }
            } yield shards.toSet.flatten

          def shardRegion(typeName: TypeName): F[ActorRef] =
            for {
              regions <- regionRefs.get
              regionRef <- regions
                .get(typeName)
                .liftTo[F](new IllegalStateException(s"Region $typeName was not found"))
            } yield regionRef

        }

        def rebalance =
          withActorContext { actorContext =>
            actorContext
              .actorSelection("*")
              .tell(RegionMsg.Rebalance, ActorRef.noSender)
          }

      }
    }
  }
}
