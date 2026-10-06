package kotlinx.coroutines.android

import kotlinx.coroutines.delay

suspend fun awaitFrame(): Long {
  delay(16)
  return System.nanoTime()
}
