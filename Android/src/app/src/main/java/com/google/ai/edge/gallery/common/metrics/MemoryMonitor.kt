/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.common.metrics

import android.os.Debug
import android.util.Log
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope

private const val TAG = "AGMemoryMonitor"
private const val KILOBYTES_PER_MEGABYTE: Float = 1024f

/**
 * Monitors process memory consumption, tracking peak and average resident memory (PSS) in
 * megabytes.
 */
class MemoryMonitor(
  samplingInterval: Duration = PeriodicSampler.DEFAULT_SAMPLING_INTERVAL,
  dispatcher: CoroutineDispatcher,
) : PeriodicSensorMonitor<MemoryMetrics> {
  private val sampler =
    PeriodicSampler(
      samplingInterval = samplingInterval,
      dispatcher = dispatcher,
      onSample = ::sample,
    )

  private data class State(
    val sampleCount: Int = 0,
    val sumMemoryMb: Double = 0.0,
    val peakMemoryMb: Float? = null,
  )

  private val state = AtomicReference(State())

  override fun start(scope: CoroutineScope) {
    reset()
    sampler.start(scope)
  }

  override fun stop(): MemoryMetrics {
    sampler.stop()
    sample()
    return buildMetrics()
  }

  override fun reset() {
    sampler.stop()
    state.set(State())
  }

  override fun sample() {
    val memoryMb = readResidentMemoryMb() ?: return
    if (memoryMb <= 0f) return

    state.updateAndGet { current ->
      val newPeak =
        if (current.peakMemoryMb == null || memoryMb > current.peakMemoryMb) {
          memoryMb
        } else {
          current.peakMemoryMb
        }
      current.copy(
        sampleCount = current.sampleCount + 1,
        sumMemoryMb = current.sumMemoryMb + memoryMb,
        peakMemoryMb = newPeak,
      )
    }
  }

  /**
   * Queries process Proportional Set Size (PSS) via [Debug.getPss] in kilobytes and converts to
   * megabytes.
   *
   * Returns null if memory stats are unavailable or not positive.
   */
  private fun readResidentMemoryMb(): Float? {
    val pssKb: Long
    try {
      pssKb = Debug.getPss()
    } catch (t: Throwable) {
      Log.d(TAG, "Failed to query process PSS: ${t.message}")
      return null
    }
    if (pssKb <= 0L) {
      return null
    }
    return pssKb / KILOBYTES_PER_MEGABYTE
  }

  override fun buildMetrics(): MemoryMetrics {
    val current = state.get()
    return memoryMetrics {
      if (current.sampleCount > 0 && current.peakMemoryMb != null) {
        this.peakMemoryMb = current.peakMemoryMb
        this.averageMemoryMb = (current.sumMemoryMb / current.sampleCount).toFloat()
      }
    }
  }
}
