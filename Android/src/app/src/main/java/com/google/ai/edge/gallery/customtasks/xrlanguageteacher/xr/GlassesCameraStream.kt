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

package com.google.ai.edge.gallery.customtasks.xrlanguageteacher.xr

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Range
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/**
 * Continuous Camera2 preview from the glasses camera, opened through the projected device context.
 *
 * Decoded, upright frames are published on [frames] so the phone screen can show what the glasses
 * see and a lesson turn can use the latest frame. Capture FPS and JPEG decoding are throttled to
 * respect glasses battery and thermal constraints.
 */
internal class GlassesCameraStream(private val context: Context) {

  private val _frames = MutableStateFlow<Bitmap?>(null)
  /** Latest decoded, upright preview frame from the glasses camera, or `null` when idle. */
  val frames: StateFlow<Bitmap?> = _frames.asStateFlow()

  /** Injects a synthetic camera frame in unit tests. */
  @VisibleForTesting
  fun setFrameForTesting(bitmap: Bitmap?) {
    _frames.value = bitmap
  }

  private val mainHandler = Handler(Looper.getMainLooper())
  /**
   * Holds the previously displayed frame for one main-thread turn before recycling so an in-flight
   * Compose draw pass never reads a recycled [Bitmap].
   */
  private var pendingRecycleBitmap: Bitmap? = null

  private var frameThread: HandlerThread? = null
  private var frameHandler: Handler? = null
  private var device: CameraDevice? = null
  private var session: CameraCaptureSession? = null
  @Volatile private var reader: ImageReader? = null
  @Volatile private var isStreaming = false
  @Volatile private var lastDecodedFrameUptimeMs = -MIN_FRAME_DECODE_INTERVAL_MS

  /** Opens the glasses camera from [glassesContext] and starts streaming. No-op if streaming. */
  @SuppressLint("MissingPermission")
  fun start(glassesContext: Context) {
    if (isStreaming) return
    val cameraManager = glassesContext.getSystemService(CameraManager::class.java)
    if (cameraManager == null) {
      Log.w(TAG, "Cannot start glasses camera: no CameraManager")
      return
    }
    val cameraInfo = findGlassesCamera(cameraManager) ?: return
    Log.i(
      TAG,
      "Starting glasses camera: id=${cameraInfo.id}, orientation=${cameraInfo.sensorOrientation}, " +
        "fpsRange=${cameraInfo.targetFpsRange}",
    )

    isStreaming = true
    lastDecodedFrameUptimeMs = -MIN_FRAME_DECODE_INTERVAL_MS
    val handlerThread = HandlerThread("AGXrGlassesFrames").apply { start() }
    val handler = Handler(handlerThread.looper)
    frameThread = handlerThread
    frameHandler = handler

    val newReader = ImageReader.newInstance(WIDTH, HEIGHT, ImageFormat.JPEG, /* maxImages= */ 2)
    reader = newReader
    newReader.setOnImageAvailableListener(
      { r -> onImageAvailable(r, cameraInfo.sensorOrientation) },
      handler,
    )

    try {
      cameraManager.openCamera(
        cameraInfo.id,
        ContextCompat.getMainExecutor(context),
        object : CameraDevice.StateCallback() {
          override fun onOpened(camera: CameraDevice) {
            if (!isStreaming || reader !== newReader) {
              try {
                camera.close()
              } catch (_: Exception) {}
              return
            }
            device = camera
            createPreviewSession(camera, newReader, cameraInfo.targetFpsRange)
          }

          override fun onDisconnected(camera: CameraDevice) {
            Log.i(TAG, "Glasses camera disconnected")
            if (reader === newReader) {
              stop()
            } else {
              runCatching { camera.close() }
            }
          }

          override fun onError(camera: CameraDevice, error: Int) {
            Log.e(TAG, "Glasses camera error $error")
            if (reader === newReader) {
              stop()
            } else {
              runCatching { camera.close() }
            }
          }
        },
      )
    } catch (e: Exception) {
      Log.e(TAG, "Failed to open glasses camera", e)
      stop()
    }
  }

  /** Stops streaming, releases the camera and frame thread, and recycles cached frames. */
  fun stop() {
    isStreaming = false
    lastDecodedFrameUptimeMs = -MIN_FRAME_DECODE_INTERVAL_MS
    try {
      session?.stopRepeating()
      session?.close()
    } catch (_: Exception) {}
    session = null

    try {
      device?.close()
    } catch (_: Exception) {}
    device = null

    try {
      reader?.close()
    } catch (_: Exception) {}
    reader = null

    frameHandler = null
    frameThread?.quitSafely()
    frameThread = null

    val displaced = _frames.getAndUpdate { null }
    mainHandler.post {
      pendingRecycleBitmap?.recycle()
      pendingRecycleBitmap = null
      displaced?.recycle()
    }
  }

  /**
   * Queues a superseded [bitmap] to be recycled on the next main-thread message turn so any
   * in-flight UI draw pass finishes reading it first.
   */
  private fun retireBitmap(bitmap: Bitmap) {
    mainHandler.post {
      pendingRecycleBitmap?.recycle()
      pendingRecycleBitmap = bitmap
    }
  }

  /**
   * Configures a regular Camera2 capture session targeting [imageReader] and starts a repeating
   * [CameraDevice.TEMPLATE_PREVIEW] request capped by [targetFpsRange].
   */
  private fun createPreviewSession(
    camera: CameraDevice,
    imageReader: ImageReader,
    targetFpsRange: Range<Int>?,
  ) {
    val sessionConfig =
      SessionConfiguration(
        SessionConfiguration.SESSION_REGULAR,
        listOf(OutputConfiguration(imageReader.surface)),
        ContextCompat.getMainExecutor(context),
        object : CameraCaptureSession.StateCallback() {
          override fun onConfigured(configuredSession: CameraCaptureSession) {
            if (!isStreaming || reader !== imageReader) {
              try {
                configuredSession.close()
              } catch (_: Exception) {}
              return
            }
            session = configuredSession
            try {
              val request =
                camera
                  .createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                  .apply {
                    addTarget(imageReader.surface)
                    if (targetFpsRange != null) {
                      set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, targetFpsRange)
                    }
                  }
                  .build()
              configuredSession.setRepeatingRequest(request, null, mainHandler)
            } catch (e: Exception) {
              Log.w(TAG, "Failed to start the glasses camera preview", e)
            }
          }

          override fun onConfigureFailed(failedSession: CameraCaptureSession) {
            Log.w(TAG, "Glasses camera session configuration failed")
          }
        },
      )
    try {
      camera.createCaptureSession(sessionConfig)
    } catch (e: Exception) {
      Log.w(TAG, "Failed to create the glasses camera session", e)
    }
  }

  /**
   * Acquires the latest frame from [imageReader], throttles decoding to at most once every
   * [MIN_FRAME_DECODE_INTERVAL_MS], rotates by [sensorOrientation], and publishes to [frames].
   */
  private fun onImageAvailable(imageReader: ImageReader, sensorOrientation: Int) {
    val image =
      try {
        imageReader.acquireLatestImage()
      } catch (_: IllegalStateException) {
        // The reader was closed by stop().
        null
      } ?: return
    try {
      val nowMs = SystemClock.uptimeMillis()
      if (!shouldDecodeFrame(nowMs = nowMs, lastDecodedMs = lastDecodedFrameUptimeMs)) {
        return
      }
      lastDecodedFrameUptimeMs = nowMs
      val bitmap = decodeRotatedJpeg(image, sensorOrientation) ?: return
      // Recycle frames that arrive after streaming was stopped; retire superseded frames safely.
      if (isStreaming && reader === imageReader) {
        val displaced = _frames.getAndUpdate { bitmap }
        if (displaced != null && displaced !== bitmap) {
          retireBitmap(displaced)
        }
      } else {
        bitmap.recycle()
      }
    } catch (e: Exception) {
      Log.w(TAG, "Failed to decode a glasses camera frame", e)
    } finally {
      image.close()
    }
  }

  /** Metadata resolved for the selected glasses camera sensor. */
  private data class GlassesCamera(
    val id: String,
    val sensorOrientation: Int,
    val targetFpsRange: Range<Int>?,
  )

  /** Returns the back-facing glasses camera, or the first camera if none faces back. */
  private fun findGlassesCamera(cameraManager: CameraManager): GlassesCamera? =
    try {
      val ids = cameraManager.cameraIdList
      val id =
        ids.firstOrNull {
          cameraManager.getCameraCharacteristics(it)[CameraCharacteristics.LENS_FACING] ==
            CameraCharacteristics.LENS_FACING_BACK
        } ?: ids.firstOrNull()
      id?.let {
        val characteristics = cameraManager.getCameraCharacteristics(it)
        val orientation = characteristics[CameraCharacteristics.SENSOR_ORIENTATION]
        val availableRanges =
          characteristics[CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES]
        GlassesCamera(
          id = it,
          sensorOrientation = orientation ?: 0,
          targetFpsRange = selectTargetFpsRange(availableRanges?.asList().orEmpty()),
        )
      }
    } catch (e: Exception) {
      Log.w(TAG, "Failed to query glasses cameras", e)
      null
    }

  /** Decodes a JPEG [image] plane into a [Bitmap] and rotates it upright by [rotationDegrees]. */
  @Suppress("KotlinArray")
  private fun decodeRotatedJpeg(image: Image, rotationDegrees: Int): Bitmap? {
    val buffer = image.planes[0].buffer
    val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
    val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
    if (rotationDegrees == 0) return raw
    val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
    return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, /* filter= */ true).also {
      raw.recycle()
    }
  }

  internal companion object {
    private const val TAG = "AGGlassesCameraStream"
    /** Preview frame width requested from the glasses camera sensor. */
    const val WIDTH = 640
    /** Preview frame height requested from the glasses camera sensor. */
    const val HEIGHT = 480
    /** Upper bound on auto-exposure target FPS to preserve glasses battery and thermal headroom. */
    const val TARGET_MAX_FPS = 1
    /** Minimum interval between JPEG decodes (1 FPS) to avoid unnecessary CPU and bitmap churn. */
    const val MIN_FRAME_DECODE_INTERVAL_MS = 1000L

    /**
     * Picks the highest available auto-exposure FPS range whose upper bound does not exceed
     * [TARGET_MAX_FPS] (`1`), falling back to the range with the lowest upper bound if all
     * advertised ranges exceed [TARGET_MAX_FPS], or `null` when [availableRanges] is empty.
     */
    @VisibleForTesting
    internal fun selectTargetFpsRange(availableRanges: Collection<Range<Int>>): Range<Int>? =
      availableRanges
        .filter { range -> range.upper <= TARGET_MAX_FPS }
        .maxByOrNull { range -> range.upper }
        ?: availableRanges.minByOrNull { range -> range.upper }

    /**
     * Returns whether enough time ([MIN_FRAME_DECODE_INTERVAL_MS]) has elapsed since
     * [lastDecodedMs] to decode the next preview frame at [nowMs].
     */
    @VisibleForTesting
    internal fun shouldDecodeFrame(nowMs: Long, lastDecodedMs: Long): Boolean =
      lastDecodedMs == 0L || nowMs - lastDecodedMs >= MIN_FRAME_DECODE_INTERVAL_MS
  }
}
