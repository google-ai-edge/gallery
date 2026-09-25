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
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope

/**
 * Hardware power monitor for Android.
 *
 * On Android, unprivileged per-turn power telemetry is unavailable: On-Device Power Monitor (ODPM)
 * readings via [android.os.health.SystemHealthManager] are rate-limited to 20–30 second cached
 * windows with differential-privacy noise unless granted privileged system permissions, and
 * [android.os.BatteryManager] current readings reflect battery-cell charging current rather than
 * SoC load whenever connected to USB. This implementation provides a safe, no-op sensor monitor
 * returning empty [BatteryMetrics].
 */
class PowerMonitor(
  @Suppress("unused") private val context: Context,
  @Suppress("unused")
  private val samplingInterval: Duration = PeriodicSampler.DEFAULT_SAMPLING_INTERVAL,
  @Suppress("unused") private val dispatcher: CoroutineDispatcher,
) : PeriodicSensorMonitor<BatteryMetrics> {

  override fun start(scope: CoroutineScope) {}

  override fun sample() {}

  override fun stop(): BatteryMetrics = BatteryMetrics.getDefaultInstance()

  override fun buildMetrics(): BatteryMetrics = BatteryMetrics.getDefaultInstance()

  override fun reset() {}
}
