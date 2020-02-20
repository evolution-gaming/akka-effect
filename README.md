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

### Tell.scala

Represents `ActorRef.tell`

```scala
trait Tell[F[_], -A] {

  def apply(a: A, sender: Option[ActorRef] = None): F[Unit]
}
```


### Ask.scala

Represents `ActorRef.ask` pattern

```scala
trait Ask[F[_], -A, B] {

  def apply(msg: A, timeout: FiniteDuration, sender: Option[ActorRef] = None): F[B]
}
```


### Reply.scala

Represents reply pattern: `sender() ! reply`

```scala
trait Reply[F[_], -A] {

  def apply(msg: A): F[Unit]
}
```


### Receive.scala

This is what you need to implement instead of familiar `new Actor { ... }`  

```scala
trait Receive[F[_], -A, B] {

  type Stop = Boolean

  def apply(msg: A, reply: Reply[F, B]): F[Stop]
}
```


### ActorOf.scala

Constructs `Actor.scala` out of `receive: ActorCtx[F, Any, Any] => Resource[F, Option[Receive[F, Any, Any]]]`


### ActorCtx.scala

Wraps `ActorContext`

```scala
trait ActorCtx[F[_], A, B] {

  def self: ActorEffect[F, A, B]

  def dispatcher: ExecutionContextExecutor

  def setReceiveTimeout(timeout: Duration): F[Unit]

  def child(name: String): F[Option[ActorRef]]

  def children: F[Iterable[ActorRef]]

  def actorRefOf: ActorRefOf[F]
}
```


### PersistentActorOf.scala

Constructs `PersistentActor.scala` out of `receive: ActorCtx[F, Any, Any] => Resource[F, EventSourced[F, S, C, E]`
 

## Setup

```scala
resolvers += Resolver.bintrayRepo("evolutiongaming", "maven")

libraryDependencies += "com.evolutiongaming" %% "akka-effect" % "0.0.1"
```