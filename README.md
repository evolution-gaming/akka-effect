# Akka-Effect
[![Build Status](https://github.com/evolution-gaming/akka-effect/workflows/CI/badge.svg)](https://github.com/evolution-gaming/akka-effect/actions?query=workflow%3ACI) 
[![Coverage Status](https://coveralls.io/repos/evolution-gaming/akka-effect/badge.svg)](https://coveralls.io/r/evolution-gaming/akka-effect)
[![Codacy Badge](https://api.codacy.com/project/badge/Grade/bd019acfc1f04f7aae90beee7e59e15d)](https://www.codacy.com/app/evolution-gaming/akka-effect?utm_source=github.com&amp;utm_medium=referral&amp;utm_content=evolution-gaming/akka-effect&amp;utm_campaign=Badge_Grade)
[![Version](https://img.shields.io/badge/version-click-blue)](https://evolution.jfrog.io/artifactory/api/search/latestVersion?g=com.evolutiongaming&a=akka-effect-actor_2.13&repos=public)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellowgreen.svg)](https://opensource.org/licenses/MIT)

This project aims to build a bridge between [akka](https://akka.io) and pure functional code based on [cats-effect](https://typelevel.org/cats-effect)

Covered:
* [Actors](https://doc.akka.io/docs/akka/current/actors.html)
* [Persistence](https://doc.akka.io/docs/akka/current/persistence.html)

## Building blocks


### `akka-effect-actor` module 

#### [Tell.scala](actor/src/main/scala/com/evolutiongaming/akkaeffect/Tell.scala)

Represents `ActorRef.tell`

```scala
trait Tell[F[_], -A] {

  def apply(a: A, sender: Option[ActorRef] = None): F[Unit]
}
```


#### [Ask.scala](actor/src/main/scala/com/evolutiongaming/akkaeffect/Ask.scala)

Represents `ActorRef.ask` pattern

```scala
trait Ask[F[_], -A, B] {

  def apply(msg: A, timeout: FiniteDuration, sender: Option[ActorRef]): F[B]
}
```


#### [Reply.scala](actor/src/main/scala/com/evolutiongaming/akkaeffect/Reply.scala)

Represents reply pattern: `sender() ! reply`

```scala
trait Reply[F[_], -A] {

  def apply(msg: A): F[Unit]
}
```


#### [Receive.scala](actor/src/main/scala/com/evolutiongaming/akkaeffect/Receive.scala)

This is what you need to implement instead of familiar `new Actor { ... }`  

```scala
trait Receive[F[_], -A, B] {

  def apply(msg: A): F[B]

  def timeout:  F[B]
}
```


#### [ActorOf.scala](actor/src/main/scala/com/evolutiongaming/akkaeffect/ActorOf.scala)

Constructs `Actor.scala` out of `receive: ActorCtx[F] => Resource[F, Receive[F, Any]]`


#### [ActorCtx.scala](actor/src/main/scala/com/evolutiongaming/akkaeffect/ActorCtx.scala)

Wraps `ActorContext`

```scala
trait ActorCtx[F[_]] {

  def self: ActorRef

  def parent: ActorRef

  def executor: ExecutionContextExecutor

  def setReceiveTimeout(timeout: Duration): F[Unit]

  def child(name: String): F[Option[ActorRef]]

  def children: F[List[ActorRef]]

  def actorRefFactory: ActorRefFactory

  def watch[A](actorRef: ActorRef, msg: A): F[Unit]

  def unwatch(actorRef: ActorRef): F[Unit]

  def stop: F[Unit]
}
```


### `akka-effect-persistence` module

#### [PersistentActorOf.scala](persistence/src/main/scala/com/evolutiongaming/akkaeffect/persistence/PersistentActorOf.scala)

Constructs `PersistentActor.scala` out of `eventSourcedOf: ActorCtx[F] => F[EventSourced[F, S, E, C]]`


#### [EventSourced.scala](persistence/src/main/scala/com/evolutiongaming/akkaeffect/persistence/EventSourced.scala)

Describes a lifecycle of entity with regard to event sourcing, phases are: Started, Recovering, Receiving and Termination

```scala
trait EventSourced[F[_], S, E, C] {

  def eventSourcedId: EventSourcedId

  def recovery: Recovery

  def pluginIds: PluginIds

  def start: Resource[F, RecoveryStarted[F, S, E, C]]
}
```

#### [RecoveryStarted.scala](persistence/src/main/scala/com/evolutiongaming/akkaeffect/persistence/RecoveryStarted.scala)

Describes start of recovery phase
 
```scala
trait RecoveryStarted[F[_], S, E, C] {

  def apply(
    seqNr: SeqNr,
    snapshotOffer: Option[SnapshotOffer[S]]
  ): Resource[F, Recovering[F, S, E, C]]
}
```


#### [Recovering.scala](persistence/src/main/scala/com/evolutiongaming/akkaeffect/persistence/Recovering.scala)

Describes recovery phase
 
```scala
trait Recovering[F[_], S, E, C] {

  def replay: Resource[F, Replay[F, E]]

  def completed(
    seqNr: SeqNr,
    journaller: Journaller[F, E],
    snapshotter: Snapshotter[F, S]
  ): Resource[F, Receive[F, C]]
}
```


#### [Replay.scala](persistence/src/main/scala/com/evolutiongaming/akkaeffect/persistence/Replay.scala)

Used during recovery to replay events
 
```scala
trait Replay[F[_], A] {

  def apply(seqNr: SeqNr, event: A): F[Unit]
}
```


#### [Journaller.scala](persistence/src/main/scala/com/evolutiongaming/akkaeffect/persistence/Journaller.scala)

Describes communication with underlying journal

```scala
trait Journaller[F[_], -A] {

  def append: Append[F, A]

  def deleteTo: DeleteEventsTo[F]
}
```


#### [Snapshotter.scala](persistence/src/main/scala/com/evolutiongaming/akkaeffect/persistence/Snapshotter.scala)

Describes communication with underlying snapshot storage

```scala
/**
  * Describes communication with underlying snapshot storage
  *
  * @tparam A - snapshot
  */
trait Snapshotter[F[_], -A] {

  def save(seqNr: SeqNr, snapshot: A): F[F[Instant]]

  def delete(seqNr: SeqNr): F[F[Unit]]

  def delete(criteria: SnapshotSelectionCriteria): F[F[Unit]]
}
```


### `akka-effect-eventsourced` module

TODO


## Setup

in [`build.sbt`](https://www.scala-sbt.org/1.x/docs/Basic-Def.html#What+is+a+build+definition%3F)
```scala
addSbtPlugin("com.evolution" % "sbt-artifactory-plugin" % "0.0.2")

libraryDependencies += "com.evolutiongaming" %% "akka-effect-actor" % "0.1.0"

libraryDependencies += "com.evolutiongaming" %% "akka-effect-persistence" % "0.1.0"
```