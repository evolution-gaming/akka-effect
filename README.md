# Akka-Effect
[![Build Status](https://github.com/evolution-gaming/akka-effect/workflows/CI/badge.svg)](https://github.com/evolution-gaming/akka-effect/actions?query=workflow%3ACI) 
[![Build Status](https://travis-ci.org/evolution-gaming/akka-effect.svg)](https://travis-ci.org/evolution-gaming/akka-effect)
[![Coverage Status](https://coveralls.io/repos/evolution-gaming/akka-effect/badge.svg)](https://coveralls.io/r/evolution-gaming/akka-effect)
[![Codacy Badge](https://api.codacy.com/project/badge/Grade/bd019acfc1f04f7aae90beee7e59e15d)](https://www.codacy.com/app/evolution-gaming/akka-effect?utm_source=github.com&amp;utm_medium=referral&amp;utm_content=evolution-gaming/akka-effect&amp;utm_campaign=Badge_Grade)
[![Version](https://api.bintray.com/packages/evolutiongaming/maven/akka-effect/images/download.svg)](https://bintray.com/evolutiongaming/maven/akka-effect/_latestVersion)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellowgreen.svg)](https://opensource.org/licenses/MIT)

This project aims to build a bridge between pure functional code based on [cats-effect](https://typelevel.org/cats-effect) and [akka](https://akka.io)

Covered:
* [Actors](https://doc.akka.io/docs/akka/current/actors.html)
* [Persistence](https://doc.akka.io/docs/akka/current/persistence.html)

## Building blocks 

### [Tell.scala](actor/src/main/scala/com/evolutiongaming/akkaeffect/Tell.scala)

Represents `ActorRef.tell`

```scala
trait Tell[F[_], -A] {

  def apply(a: A, sender: Option[ActorRef] = None): F[Unit]
}
```


### [Ask.scala](actor/src/main/scala/com/evolutiongaming/akkaeffect/Ask.scala)

Represents `ActorRef.ask` pattern

```scala
trait Ask[F[_], -A, B] {

  def apply(msg: A, timeout: FiniteDuration, sender: Option[ActorRef] = None): F[B]
}
```


### [Reply.scala](actor/src/main/scala/com/evolutiongaming/akkaeffect/Reply.scala)

Represents reply pattern: `sender() ! reply`

```scala
trait Reply[F[_], -A] {

  def apply(msg: A): F[Unit]
}
```


### [Receive.scala](actor/src/main/scala/com/evolutiongaming/akkaeffect/Receive.scala)

This is what you need to implement instead of familiar `new Actor { ... }`  

```scala
trait Receive[F[_], -A, B] {

  type Stop = Boolean

  def apply(msg: A, reply: Reply[F, B]): F[Stop]
}
```


### [ActorOf.scala](actor/src/main/scala/com/evolutiongaming/akkaeffect/ActorOf.scala)

Constructs `Actor.scala` out of `receive: ActorCtx[F, Any, Any] => Resource[F, Option[Receive[F, Any, Any]]]`


### [ActorCtx.scala](actor/src/main/scala/com/evolutiongaming/akkaeffect/ActorCtx.scala)

Wraps `ActorContext`

```scala
trait ActorCtx[F[_], -A, B] {

  def self: ActorEffect[F, A, B]

  def dispatcher: ExecutionContextExecutor

  def setReceiveTimeout(timeout: Duration): F[Unit]

  def child(name: String): F[Option[ActorRef]]

  def children: F[Iterable[ActorRef]]

  def actorRefOf: ActorRefOf[F]

  def watch(actorRef: ActorRef, msg: A): F[Unit]

  def unwatch(actorRef: ActorRef): F[Unit]
}
```


### [PersistentActorOf.scala](persistence/src/main/scala/com/evolutiongaming/akkaeffect/persistence/PersistentActorOf.scala)

Constructs `PersistentActor.scala` out of `eventSourcedOf: ActorCtx[F, C, R] => Resource[F, EventSourced[F, S, C, E, R]`


### [EventSourced.scala](persistence/src/main/scala/com/evolutiongaming/akkaeffect/persistence/EventSourced.scala)

Describes lifecycle of entity with regards to event sourcing, phases are Started, Recovering, Receiving, Termination

```scala
trait EventSourced[F[_], S, C, E, R] {

  def id: String

  def recovery: Recovery = Recovery()

  def pluginIds: PluginIds = PluginIds.default

  def start: Resource[F, Option[Started[F, S, C, E, R]]]
}
```


### [Recovering.scala](persistence/src/main/scala/com/evolutiongaming/akkaeffect/persistence/Recovering.scala)

Describes recovery phase
 
```scala
trait Recovering[F[_], S, C, E, R] {

  def initial: F[S]

  def replay: Resource[F, Replay[F, S, E]]

  def recoveryCompleted(
    state: S,
    seqNr: SeqNr,
    journaller: Journaller[F, E],
    snapshotter: Snapshotter[F, S]
  ): Resource[F, Option[Receive[F, C, R]]]
}
```


### [Journaller.scala](persistence/src/main/scala/com/evolutiongaming/akkaeffect/persistence/Journaller.scala)

```scala
trait Journaller[F[_], -A] {

  def append: Append[F, A]

  def deleteTo(seqNr: SeqNr): F[F[Unit]]
}
```


### [Snapshotter.scala](persistence/src/main/scala/com/evolutiongaming/akkaeffect/persistence/Snapshotter.scala)

```scala
trait Snapshotter[F[_], -A] {

  def save(snapshot: A): F[Result[F]]

  def delete(seqNr: SeqNr): F[F[Unit]]

  def delete(criteria: SnapshotSelectionCriteria): F[F[Unit]]
}
```


## Setup

```scala
resolvers += Resolver.bintrayRepo("evolutiongaming", "maven")

libraryDependencies += "com.evolutiongaming" %% "akka-effect-actor" % "0.0.1"

libraryDependencies += "com.evolutiongaming" %% "akka-effect-persistence" % "0.0.1"
```