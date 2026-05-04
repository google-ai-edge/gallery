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

package com.google.ai.edge.gallery.customtasks.common

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class SteadinessMonitor(context: Context, private val steadyDurationMs: Long = 2000L) :
  SensorEventListener {
  private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
  private val gyroSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

  // If gyroscope is not available, default to stable.
  private val _isStable = MutableStateFlow(gyroSensor == null)
  val isStable: StateFlow<Boolean> = _isStable

  // Threshold: 0.1 rad/s is quite steady.
  private val STABILITY_THRESHOLD = 0.1f

  fun start() {
    gyroSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
  }

  fun stop() {
    sensorManager.unregisterListener(this)
  }

  private var steadyStartTime: Long? = null

  override fun onSensorChanged(event: SensorEvent?) {
    if (event?.sensor?.type == Sensor.TYPE_GYROSCOPE) {
      val x = event.values[0]
      val y = event.values[1]
      val z = event.values[2]

      val magnitude = sqrt(x * x + y * y + z * z)

      if (magnitude < STABILITY_THRESHOLD) {
        if (steadyStartTime == null) {
          steadyStartTime = System.currentTimeMillis()
        }
        val start = steadyStartTime
        if (start != null && System.currentTimeMillis() - start >= steadyDurationMs) {
          _isStable.value = true
        } else {
          _isStable.value = false
        }
      } else {
        steadyStartTime = null
        _isStable.value = false
      }
    }
  }

  override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
