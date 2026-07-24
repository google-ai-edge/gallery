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

package com.google.ai.edge.gallery.customtasks.livevideo

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject

data class CameraState(
  val zoomRatio: Float = 1.0f,
  val targetZoom: Float = 1.0f,
  val frameCenterX: Float = 0.5f,
  val frameCenterY: Float = 0.5f,
  val activeMode: String = "auto",
  val lastCaptureTimeMs: Long = 0,
  val captureCount: Int = 0,
  val autoZoomEnabled: Boolean = false,
  val autoModeEnabled: Boolean = false,
  val autoCaptureEnabled: Boolean = false,
  val autoCaptureCondition: String = "",
)

sealed class CameraAction {
  data class SetMode(val mode: String) : CameraAction()

  data class Capture(val reason: String) : CameraAction()

  data class Frame(val zoom: Float, val centerX: Float, val centerY: Float) : CameraAction()
}

class CameraController(private val context: Context) {
  companion object {
    private const val TAG = "CameraCtrl"
    private const val MIN_CAPTURE_INTERVAL_MS = 2000L
  }

  private val _state = MutableStateFlow(CameraState())
  val state: StateFlow<CameraState> = _state.asStateFlow()

  fun parseAndExecute(jsonResponse: String): CameraAction? {
    val action = parseJson(jsonResponse) ?: return null
    execute(action)
    return action
  }

  fun execute(action: CameraAction) {
    when (action) {
      is CameraAction.SetMode -> {
        Log.d(TAG, "Camera mode -> ${action.mode}")
        _state.update { it.copy(activeMode = action.mode) }
      }
      is CameraAction.Capture -> {
        val now = System.currentTimeMillis()
        if (now - _state.value.lastCaptureTimeMs < MIN_CAPTURE_INTERVAL_MS) {
          Log.d(TAG, "Capture skipped (too soon)")
          return
        }
        Log.d(TAG, "AUTO-CAPTURE: ${action.reason}")
        hapticFeedback()
        _state.update { it.copy(lastCaptureTimeMs = now, captureCount = it.captureCount + 1) }
      }
      is CameraAction.Frame -> {
        Log.d(TAG, "Frame -> zoom=${action.zoom}, center=(${action.centerX}, ${action.centerY})")
        _state.update {
          it.copy(
            targetZoom = action.zoom.coerceIn(1f, 5f),
            frameCenterX = action.centerX.coerceIn(0f, 1f),
            frameCenterY = action.centerY.coerceIn(0f, 1f),
          )
        }
        smoothZoomTo(action.zoom)
      }
    }
  }

  fun updateAutoZoomFromGaze(cellHighlights: List<Pair<Float, Float>>) {
    if (!_state.value.autoZoomEnabled || cellHighlights.isEmpty()) return

    val cx = cellHighlights.map { it.first }.average().toFloat()
    val cy = cellHighlights.map { it.second }.average().toFloat()

    val spreadX = cellHighlights.maxOf { it.first } - cellHighlights.minOf { it.first }
    val spreadY = cellHighlights.maxOf { it.second } - cellHighlights.minOf { it.second }
    val spread = maxOf(spreadX, spreadY)

    val targetZoom =
      when {
        spread < 0.2f -> 2.5f
        spread < 0.4f -> 1.8f
        spread < 0.6f -> 1.3f
        else -> 1.0f
      }

    _state.update { it.copy(targetZoom = targetZoom, frameCenterX = cx, frameCenterY = cy) }
    smoothZoomTo(targetZoom)
  }

  fun setAutoZoom(enabled: Boolean) {
    _state.update { it.copy(autoZoomEnabled = enabled) }
    if (!enabled) smoothZoomTo(1.0f)
  }

  fun setAutoCapture(enabled: Boolean, condition: String = "") {
    _state.update { it.copy(autoCaptureEnabled = enabled, autoCaptureCondition = condition) }
  }

  private fun smoothZoomTo(target: Float) {
    val clamped = target.coerceIn(1f, 5f)
    _state.update { it.copy(targetZoom = clamped) }
  }

  fun saveFrameToGallery(bitmap: Bitmap, reason: String): Boolean {
    val filename = "GemmaGaze_${System.currentTimeMillis()}.jpg"
    val values =
      ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, filename)
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        put(MediaStore.Images.Media.DESCRIPTION, "Auto-capture: $reason")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/GemmaGaze")
        }
      }
    var uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
    return try {
      uri?.let {
        context.contentResolver.openOutputStream(it)?.use { out ->
          bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
        }
        Log.d(TAG, "Photo saved: $filename")
        true
      } ?: false
    } catch (e: Exception) {
      Log.e(TAG, "Failed to save photo", e)
      uri?.let { context.contentResolver.delete(it, null, null) }
      false
    }
  }

  @Suppress("NewApi")
  private fun hapticFeedback() {
    try {
      val vibrator =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
          val mgr = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
          mgr.defaultVibrator
        } else {
          @Suppress("DEPRECATION")
          context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
      vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
    } catch (e: Exception) {
      Log.w(TAG, "Haptic feedback failed", e)
    }
  }

  private fun parseJson(text: String): CameraAction? {
    val jsonPattern = """\{[^{}]*\}""".toRegex()
    val match = jsonPattern.find(text) ?: return null

    return try {
      val json = JSONObject(match.value)
      val actionType = json.optString("action", "")

      when {
        actionType == "capture" || json.has("capture") -> {
          CameraAction.Capture(json.optString("reason", "auto-capture"))
        }
        actionType == "mode" || (json.has("mode") && !json.has("zoom")) -> {
          CameraAction.SetMode(json.optString("mode", "auto"))
        }
        actionType == "frame" || json.has("zoom") -> {
          CameraAction.Frame(
            zoom = json.optDouble("zoom", 1.0).toFloat(),
            centerX = json.optDouble("center_x", 0.5).toFloat(),
            centerY = json.optDouble("center_y", 0.5).toFloat(),
          )
        }
        actionType == "wait" -> null
        else -> null
      }
    } catch (e: Exception) {
      Log.w(TAG, "Failed to parse camera action: ${match.value}", e)
      null
    }
  }
}
