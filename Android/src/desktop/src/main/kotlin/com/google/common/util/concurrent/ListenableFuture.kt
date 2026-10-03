package com.google.common.util.concurrent

import java.util.concurrent.Executor
import java.util.concurrent.Future

interface ListenableFuture<V> : Future<V> {
  fun addListener(listener: Runnable, executor: Executor)
}
