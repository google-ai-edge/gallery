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

import android.content.Context
import android.os.Build
import android.os.OutcomeReceiver
import android.os.PowerMonitor as OsPowerMonitor
import android.os.PowerMonitorReadings
import android.os.health.SystemHealthManager
import android.util.Log
import androidx.annotation.RequiresApi
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asExecutor

private const val TAG = "AGPowerMonitor"

/** Monitors hardware power consumption, tracking peak and average power draw in milliwatts. */
class PowerMonitor(
  private val context: Context,
  samplingInterval: Duration = PeriodicSampler.DEFAULT_SAMPLING_INTERVAL,
  dispatcher: CoroutineDispatcher,
) : PeriodicSensorMonitor<BatteryMetrics> {

  private val sampler =
    PeriodicSampler(
      samplingInterval = samplingInterval,
      dispatcher = dispatcher,
      onSample = ::sample,
    )

  // ODPM hardware power rails require Android 15 (API level 35) or higher.
  private val isSupported: Boolean = Build.VERSION.SDK_INT >= 35
  private val systemHealthManager: SystemHealthManager? =
    if (isSupported) {
      context.getSystemService(Context.SYSTEM_HEALTH_SERVICE) as? SystemHealthManager
    } else {
      null
    }
  private val supportedPowerMonitors = CopyOnWriteArrayList<OsPowerMonitor>()
  private val lastEnergyReadings = ConcurrentHashMap<OsPowerMonitor, EnergyReading>()
  private val odpmExecutor = dispatcher.asExecutor()

  init {
    if (isSupported && systemHealthManager != null) {
      try {
        systemHealthManager.getSupportedPowerMonitors(odpmExecutor) { monitors ->
          supportedPowerMonitors.addAll(monitors)
        }
      } catch (e: Throwable) {
        Log.w(TAG, "Could not query supported ODPM power monitors: ${e.message}")
      }
    }
  }

  private data class EnergyReading(val consumedEnergyUws: Long, val timestampMs: Long)

  private data class State(
    val sampleCount: Int = 0,
    val sumPowerMw: Double = 0.0,
    val peakPowerMw: Float? = null,
  )

  private val state = AtomicReference(State())

  override fun start(scope: CoroutineScope) {
    reset()
    sampler.start(scope)
  }

  /**
   * Stops periodic sampling and returns aggregated metrics.
   *
   * Note that [sample] is dispatched asynchronously via [OutcomeReceiver] on [odpmExecutor], so
   * this builds metrics from all samples collected up to the stopping point.
   */
  override fun stop(): BatteryMetrics {
    sampler.stop()
    sample()
    return buildMetrics()
  }

  override fun reset() {
    sampler.stop()
    lastEnergyReadings.clear()
    state.set(State())
  }

  override fun sample() {
    if (!isSupported || systemHealthManager == null || supportedPowerMonitors.isEmpty()) {
      return
    }

    systemHealthManager.getPowerMonitorReadings(
      supportedPowerMonitors,
      odpmExecutor,
      object : OutcomeReceiver<PowerMonitorReadings, RuntimeException> {
        override fun onResult(readings: PowerMonitorReadings) {
          val powerMw = computeInstantaneousPowerMw(readings)
          if (powerMw != null && powerMw > 0f) {
            recordSample(powerMw)
          }
        }

        override fun onError(error: RuntimeException) {
          Log.d(TAG, "ODPM reading failed: ${error.message}")
        }
      },
    )
  }

  /**
   * Computes the aggregated instantaneous power consumption across all monitored hardware rails in
   * milliwatts (mW).
   *
   * ## Calculation & Unit Conversion
   * 1. ODPM reports cumulative energy per rail in microwatt-seconds ($\mu\text{W}\cdot\text{s}$ or
   *    $\mu\text{J}$) via [PowerMonitorReadings.getConsumedEnergy].
   * 2. Timestamp per rail is provided in milliseconds (ms) via
   *    [PowerMonitorReadings.getTimestampMillis].
   * 3. Power is derived from the energy differential over elapsed time: $$\text{Power (mW)} =
   *    \frac{\Delta\text{Energy } (\mu\text{W}\cdot\text{s})}{\Delta\text{Time } (\text{ms})} =
   *    \frac{10^{-6}\text{ W}\cdot\text{s}}{10^{-3}\text{ s}} = 10^{-3}\text{ W} = 1\text{ mW}$$
   *
   * @param readings The latest ODPM power readings from [SystemHealthManager].
   * @return Aggregated power in milliwatts, or null if no valid delta could be computed.
   */
  @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
  private fun computeInstantaneousPowerMw(readings: PowerMonitorReadings): Float? {
    var totalPowerMw = 0.0
    var validRailCount = 0

    for (monitor in supportedPowerMonitors) {
      val consumedEnergy = readings.getConsumedEnergy(monitor)
      val timestampMs = readings.getTimestampMillis(monitor)
      if (
        consumedEnergy == PowerMonitorReadings.ENERGY_UNAVAILABLE.toLong() || consumedEnergy < 0L
      ) {
        continue
      }

      val prev = lastEnergyReadings.put(monitor, EnergyReading(consumedEnergy, timestampMs))
      if (prev != null) {
        val deltaEnergy = consumedEnergy - prev.consumedEnergyUws
        val deltaTimeMs = timestampMs - prev.timestampMs
        if (deltaTimeMs > 0L && deltaEnergy >= 0L) {
          val railPowerMw = deltaEnergy.toDouble() / deltaTimeMs.toDouble()
          totalPowerMw += railPowerMw
          validRailCount++
        }
      }
    }

    return if (validRailCount > 0 && totalPowerMw > 0.0) totalPowerMw.toFloat() else null
  }

  private fun recordSample(powerMw: Float) {
    state.updateAndGet { current ->
      val newPeak =
        if (current.peakPowerMw == null || powerMw > current.peakPowerMw) {
          powerMw
        } else {
          current.peakPowerMw
        }
      current.copy(
        sampleCount = current.sampleCount + 1,
        sumPowerMw = current.sumPowerMw + powerMw,
        peakPowerMw = newPeak,
      )
    }
  }

  override fun buildMetrics(): BatteryMetrics {
    if (!isSupported) {
      return BatteryMetrics.getDefaultInstance()
    }
    val current = state.get()
    return batteryMetrics {
      if (current.sampleCount > 0 && current.peakPowerMw != null) {
        this.peakPowerMw = current.peakPowerMw
        this.averagePowerMw = (current.sumPowerMw / current.sampleCount).toFloat()
      }
    }
  }
}
