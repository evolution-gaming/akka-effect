package com.evolutiongaming.akkaeffect

import scala.concurrent.ExecutionContext

object ParasiticExecutionContext {
  def apply(): ExecutionContext = ExecutionContext.parasitic
}
