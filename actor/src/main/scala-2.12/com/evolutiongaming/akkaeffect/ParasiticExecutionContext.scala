package com.evolutiongaming.akkaeffect

import scala.concurrent.ExecutionContext.defaultReporter
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}

object ParasiticExecutionContext {

  def apply(): ExecutionContext = Instance

  private object Instance extends ExecutionContextExecutor {

    def execute(runnable: Runnable): Unit = runnable.run()

    def reportFailure(t: Throwable): Unit = defaultReporter(t)
  }
}