package com.evolutiongaming.akkaeffect.cluster.sharding

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import akka.actor.{Actor, ActorContext, ActorRef, ActorSystem, Props, Status}
import akka.cluster.sharding.ShardCoordinator.ShardAllocationStrategy
import akka.cluster.sharding.ShardRegion.ShardId
import akka.cluster.sharding.{ClusterShardingSettings, ShardRegion}
import cats.effect.{Concurrent, Resource}
import cats.syntax.all._
import com.evolutiongaming.akkaeffect.{ActorRefOf, Ask}
import com.evolutiongaming.catshelper.CatsHelper._
import com.evolutiongaming.akkaeffect.cluster.{DataCenter, Role}
import com.evolutiongaming.akkaeffect.persistence.TypeName
import com.evolutiongaming.akkaeffect.util.Terminated
import com.evolutiongaming.catshelper.{FromFuture, ToFuture, ToTry}

import scala.concurrent.Promise
import scala.concurrent.duration._
import scala.util.Try


trait ClusterShardingLocal[F[_]] {

  def clusterSharding: ClusterSharding[F]

  def rebalance: F[Unit]
}

object ClusterShardingLocal {

  def of[F[_]: Concurrent: ToFuture: FromFuture: ToTry](
    actorSystem: ActorSystem
  ): Resource[F, ClusterShardingLocal[F]] = {

    def actorNameOf(a: String) = URLEncoder.encode(a, StandardCharsets.UTF_8.name())

    case class ShardingMsg(f: ActorContext => Unit)


    sealed trait RegionMsg

    object RegionMsg {

      final case object Rebalance extends RegionMsg
      final case object State extends RegionMsg
    }


    def shardingActor(): Actor = new Actor {

      def receive = {
        case ShardingMsg(f) => f(context)
      }
    }

    val actorRefOf = ActorRefOf.fromActorRefFactory[F](actorSystem)
    val terminated = Terminated(actorRefOf)

    actorRefOf
      .apply(Props(shardingActor()))
      .map { shardingRef =>

        def withActorContext[A](f: ActorContext => A) = {
          FromFuture[F].apply {
            val promise = Promise[A]()

            def f1(context: ActorContext): Unit = {
              val result = Try { f(context) }
              promise.complete(result)
            }

            shardingRef.tell(ShardingMsg(f1), ActorRef.noSender)
            promise.future
          }
        }

        def actorOf(name: String, props: => Props) = {
          Resource.make {
            withActorContext { actorContext =>
              actorContext
                .child(name)
                .getOrElse { actorContext.actorOf(props, name) }
            }
          } { actorRef =>
            for {
              _ <- withActorContext { _.stop(actorRef) }
              _ <- terminated(actorRef)
            } yield {}
          }
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
              handOffStopMessage: A
            ) = {

              def shardActor(): Actor = new Actor {

                def receive = {
                  case a: ShardRegion.Passivate =>
                    sender().tell(a.stopMessage, self)

                  case a: ShardRegion.Msg if extractEntityId.isDefinedAt(a) =>
                    val (entityId, msg) = extractEntityId(a)
                    val entityName = actorNameOf(entityId)
                    context
                      .child(entityId)
                      .getOrElse { context.actorOf(props, entityName) }
                      .forward(msg)
                }
              }


              def regionActor(): Actor = new Actor {

                private implicit val executor = context.dispatcher

                def allocation(): Map[ActorRef, Vector[ShardId]] = {
                  val shardIds = context
                    .children
                    .toList
                    .map { _.path.name }
                    .toVector
                  Map((self, shardIds))
                }

                def receive: Receive = {

                  case RegionMsg.Rebalance => allocationStrategy
                    .rebalance(allocation(), Set.empty)
                    .onComplete { _ =>
                      context
                        .actorSelection("*/*")
                        .tell(handOffStopMessage, self)
                    }

                  case RegionMsg.State =>
                    val shards = allocation().values.flatten.toList
                    context.sender().tell(shards, context.self)

                  case msg: ShardRegion.Msg =>
                    val sender = context.sender()
                    val shardId = extractShardId(msg)
                    val shardName = actorNameOf(shardId)

                    def allocate = allocationStrategy.allocateShard(sender, shardId, allocation())

                    context
                      .child(shardName)
                      .fold {
                        FromFuture[F]
                          .apply(allocate)
                          .toTry
                          .as(context.actorOf(Props(shardActor()), shardName))
                      } { shard =>
                        shard.pure[Try]
                      }
                      .fold(
                        error => sender.tell(Status.Failure(error), sender),
                        shard => shard.tell(msg, sender))
                }
              }

              val regionName = actorNameOf(typeName.value)
              actorOf(regionName, Props(regionActor()))
            }

            def startProxy(
              typeName: TypeName,
              role: Option[Role],
              dataCenter: Option[DataCenter],
              extractEntityId: ShardRegion.ExtractEntityId,
              extractShardId: ShardRegion.ExtractShardId
            ) = {

              val regionName = actorNameOf(typeName.value)

              def regionProxyActor(): Actor = new Actor {

                def receive: Receive = {
                  case _: RegionMsg =>

                  case msg: ShardRegion.Msg =>
                    val shardId = extractShardId(msg)
                    val shardName = actorNameOf(shardId)
                    context
                      .actorSelection(s"../$regionName/$shardName")
                      .forward(msg)
                }
              }

              val regionProxyName = s"${ regionName }Proxy"
              actorOf(regionProxyName, Props(regionProxyActor()))
            }

            def localShards: F[List[String]] =
              for {
                regions <- withActorContext(_.children.filterNot(_.path.name.endsWith("Proxy")))
                shards <- regions.toList
                  .map(Ask.fromActorRef[F])
                  .traverse { ask =>
                    ask(RegionMsg.State, 1.second).flatten
                  }
              } yield shards.collect { case shards: List[String] => shards }.flatten
          }

          def rebalance = {
            withActorContext { actorContext =>
              actorContext
                .actorSelection("*")
                .tell(RegionMsg.Rebalance, ActorRef.noSender)
            }
          }
        }
      }
  }
}
