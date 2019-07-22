# Akka-Effect [![Build Status](https://travis-ci.org/evolution-gaming/akka-effect.svg)](https://travis-ci.org/evolution-gaming/akka-effect) [![Coverage Status](https://coveralls.io/repos/evolution-gaming/akka-effect/badge.svg)](https://coveralls.io/r/evolution-gaming/akka-effect) [![Codacy Badge](https://api.codacy.com/project/badge/Grade/bd019acfc1f04f7aae90beee7e59e15d)](https://www.codacy.com/app/evolution-gaming/akka-effect?utm_source=github.com&amp;utm_medium=referral&amp;utm_content=evolution-gaming/akka-effect&amp;utm_campaign=Badge_Grade) [ ![version](https://api.bintray.com/packages/evolutiongaming/maven/akka-effect/images/download.svg) ](https://bintray.com/evolutiongaming/maven/akka-effect/_latestVersion) [![License: MIT](https://img.shields.io/badge/License-MIT-yellowgreen.svg)](https://opensource.org/licenses/MIT)

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

  def apply(a: A, timeout: FiniteDuration, sender: Option[ActorRef] = None): F[B]
}
```


### Reply.scala

Represents reply patter

```scala
trait Reply[F[_], -A] {

  def apply(a: A): F[Unit]
}
```


### ActorOf.scala

Constructs `Actor.scala` out of `receive: ActorCtx.Any[F] => F[Option[Receive.Any[F]]]`


### ActorCtx.scala

Wraps `ActorContext`

```scala
trait ActorCtx[F[_], A, B] {

  def self: ActorEffect[F, A, B]

  def dispatcher: ExecutionContextExecutor

  def setReceiveTimeout(timeout: Duration): F[Unit]

  def child(name: String): F[Option[ActorRef]]

  def children: F[Iterable[ActorRef]]

  def actorOf: ActorRefOf[F]
}
```
 

## Setup

```scala
resolvers += Resolver.bintrayRepo("evolutiongaming", "maven")

libraryDependencies += "com.evolutiongaming" %% "akka-effect" % "0.0.1"
```