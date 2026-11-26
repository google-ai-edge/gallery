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

package com.google.ai.edge.gallery.customtasks.arlanguageteacher

import android.app.Activity
import android.app.Application
import android.companion.virtual.VirtualDeviceManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.annotation.MainThread
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import androidx.xr.projected.ProjectedContext
import androidx.xr.projected.experimental.ExperimentalProjectedApi
import com.google.ai.edge.gallery.di.ApplicationScope
import com.google.ai.edge.gallery.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Projected glasses virtual device ID and any ungranted permissions requested on it. */
data class GlassesPermissionRequest(val deviceId: Int, val missingPermissions: List<String>)

/** Touchpad gestures recognized on the projected glasses activity. */
enum class GlassesGesture {
  /** Single tap on the glasses touchpad (scan new object / submit utterance). */
  TAP,
  /** Horizontal forward swipe on the glasses touchpad (ask follow-up question). */
  SWIPE_FORWARD,
}

/**
 * Manages the app's use of audio-only AI glasses in XR Language Teacher.
 * - Tracks whether a projected (glasses) device is connected, while [startMonitoringConnection] is
 *   active.
 * - Launches [GlassesTapActivity] on the glasses so touchpad gestures reach the app, and forwards
 *   each gesture to the registered listener.
 * - Owns the glasses camera preview ([GlassesCameraStream]), glasses mic capture
 *   ([GlassesMicStream]), and earcons ([GlassesEarcons]).
 */
@OptIn(ExperimentalProjectedApi::class)
@Singleton
class XrGlassesManager
@Inject
constructor(
  @ApplicationContext private val context: Context,
  @ApplicationScope private val applicationScope: CoroutineScope,
  @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
  @VisibleForTesting
  constructor(
    context: Context
  ) : this(context, CoroutineScope(SupervisorJob() + Dispatchers.IO), Dispatchers.IO)

  companion object {
    private const val TAG = "AGXrGlassesManager"
    /** Gestures that arrive within this window of the previous gesture are ignored. */
    private const val GESTURE_DEBOUNCE_MS = 300L
    /** Intent extra telling GlassesCore to wake the glasses display and avoid immediate SNOOZE. */
    internal const val EXTRA_WAKE_SCREEN = "com.google.android.glasses.core.option.WAKE_SCREEN"

    /** The live manager, used by [GlassesTapActivity] to forward touchpad gestures. */
    @Volatile var activeInstance: XrGlassesManager? = null
  }

  private val mainHandler = Handler(Looper.getMainLooper())
  private val audioManager = context.getSystemService(AudioManager::class.java)
  private val virtualDeviceManager =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      context.getSystemService(VirtualDeviceManager::class.java)
    } else {
      null
    }

  private val cameraStream = GlassesCameraStream(context)
  private val micStream = GlassesMicStream(applicationScope, ioDispatcher)
  private val earcons = GlassesEarcons()

  private val _isGlassesConnected = MutableStateFlow(false)
  val isGlassesConnected: StateFlow<Boolean> = _isGlassesConnected.asStateFlow()

  /** Latest upright frame from the glasses camera, or null when it isn't streaming. */
  val glassesCameraBitmap: StateFlow<Bitmap?> = cameraStream.frames

  /** When non-null, replaces the detected connection state. Set only by tests. */
  private var connectedOverrideForTesting: Boolean? = null

  @VisibleForTesting
  fun setGlassesConnectedForTesting(connected: Boolean) {
    connectedOverrideForTesting = connected
    _isGlassesConnected.value = connected
  }

  @VisibleForTesting
  fun setGlassesCameraBitmapForTesting(bitmap: Bitmap?) {
    cameraStream.setFrameForTesting(bitmap)
  }

  @VisibleForTesting
  var lastPlayedEarcon: XrEarconType? = null
    private set

  @Volatile private var currentHostActivity: WeakReference<Activity>? = null
  private var lastGestureTimeMs = -GESTURE_DEBOUNCE_MS
  private var onGlassesGestureListener: ((GlassesGesture) -> Unit)? = null

  private var audioDeviceCallback: AudioDeviceCallback? = null
  private var virtualDeviceListener: VirtualDeviceManager.VirtualDeviceListener? = null
  /** Number of [startMonitoringConnection] calls not yet balanced by [stopMonitoringConnection]. */
  private var connectionMonitorCount = 0

  init {
    activeInstance = this
    (context as? Application)?.registerActivityLifecycleCallbacks(
      object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
          if (activity !is GlassesTapActivity) currentHostActivity = WeakReference(activity)
        }

        override fun onActivityStarted(activity: Activity) {
          if (activity !is GlassesTapActivity) currentHostActivity = WeakReference(activity)
        }

        override fun onActivityResumed(activity: Activity) {
          if (activity !is GlassesTapActivity) currentHostActivity = WeakReference(activity)
        }

        override fun onActivityPaused(activity: Activity) {}

        override fun onActivityStopped(activity: Activity) {}

        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

        override fun onActivityDestroyed(activity: Activity) {
          if (currentHostActivity?.get() === activity) currentHostActivity = null
        }
      }
    )
  }

  // -----------------------------------------------------------------------------------------------
  // Connection
  // -----------------------------------------------------------------------------------------------

  /**
   * Re-checks the connection now and whenever an audio device or a virtual device comes or goes.
   * Nothing is monitored until this is called. Calls are counted, and monitoring stays active until
   * each call is balanced by [stopMonitoringConnection].
   */
  @MainThread
  fun startMonitoringConnection() {
    connectionMonitorCount++
    updateGlassesConnectionState()

    if (audioDeviceCallback == null) {
      val callback =
        object : AudioDeviceCallback() {
          override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            updateGlassesConnectionState()
          }

          override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            updateGlassesConnectionState()
          }
        }
      audioDeviceCallback = callback
      audioManager?.registerAudioDeviceCallback(callback, mainHandler)
    }

    if (
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM &&
        virtualDeviceListener == null &&
        virtualDeviceManager != null
    ) {
      val listener =
        object : VirtualDeviceManager.VirtualDeviceListener {
          override fun onVirtualDeviceCreated(deviceId: Int) {
            updateGlassesConnectionState()
          }

          override fun onVirtualDeviceClosed(deviceId: Int) {
            updateGlassesConnectionState()
          }
        }
      virtualDeviceListener = listener
      virtualDeviceManager.registerVirtualDeviceListener(
        ContextCompat.getMainExecutor(context),
        listener,
      )
    }
  }

  /**
   * Balances one [startMonitoringConnection] call. The device callbacks are unregistered once every
   * call is balanced.
   */
  @MainThread
  fun stopMonitoringConnection() {
    if (connectionMonitorCount == 0) return
    connectionMonitorCount--
    if (connectionMonitorCount > 0) return

    audioDeviceCallback?.let { audioManager?.unregisterAudioDeviceCallback(it) }
    audioDeviceCallback = null
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
      virtualDeviceListener?.let { virtualDeviceManager?.unregisterVirtualDeviceListener(it) }
    }
    virtualDeviceListener = null
  }

  fun updateGlassesConnectionState() {
    _isGlassesConnected.value = connectedOverrideForTesting ?: (getGlassesContext() != null)
  }

  /** Returns a context for the connected glasses (projected) device, or null if none. */
  fun getGlassesContext(): Context? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
    return try {
      ProjectedContext.createProjectedDeviceContext(context)
    } catch (_: Exception) {
      null
    }
  }

  /**
   * Returns the projected device ID and any permissions from [permissions] not yet granted on the
   * connected glasses virtual device, or `null` if no glasses device permission prompt is needed.
   */
  fun checkMissingGlassesPermissions(permissions: List<String>): GlassesPermissionRequest? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return null
    val glassesCtx = getGlassesContext() ?: return null
    val missing = permissions.filter {
      glassesCtx.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
    }
    return if (missing.isNotEmpty()) {
      GlassesPermissionRequest(deviceId = glassesCtx.deviceId, missingPermissions = missing)
    } else {
      null
    }
  }

  // -----------------------------------------------------------------------------------------------
  // Camera
  // -----------------------------------------------------------------------------------------------

  /** Starts streaming the glasses camera into [glassesCameraBitmap]. */
  fun startGlassesCameraStreaming() {
    val glassesCtx = getGlassesContext()
    if (glassesCtx == null) {
      Log.w(TAG, "Cannot start the glasses camera: no glasses context")
      return
    }
    cameraStream.start(glassesCtx)
  }

  fun stopGlassesCameraStreaming() {
    cameraStream.stop()
  }

  // -----------------------------------------------------------------------------------------------
  // Gestures
  // -----------------------------------------------------------------------------------------------

  /**
   * Sets [listener] as the target for glasses touchpad gestures (replacing any previous listener)
   * and launches [GlassesTapActivity] on the glasses if it isn't running yet.
   */
  fun registerGlassesGestureListener(listener: (GlassesGesture) -> Unit) {
    onGlassesGestureListener = listener
    Log.i(
      TAG,
      "registerGlassesGestureListener: tapActivityRunning=${GlassesTapActivity.isRunning()}, " +
        "hasHostActivity=${currentHostActivity?.get() != null}",
    )
    launchGlassesTapActivity()
  }

  /** Convenience wrapper registering a listener that handles only [GlassesGesture.TAP]. */
  fun registerGlassesTapListener(listener: () -> Unit) {
    registerGlassesGestureListener { gesture ->
      if (gesture == GlassesGesture.TAP) {
        listener()
      }
    }
  }

  fun unregisterGlassesTapListener() {
    onGlassesGestureListener = null
    stopGlassesMicStream()
    GlassesTapActivity.finishIfRunning()
  }

  /** Called by [GlassesTapActivity] for each recognized touchpad gesture. */
  internal fun dispatchGesture(gesture: GlassesGesture) {
    val now = SystemClock.uptimeMillis()
    val isDebounced = now - lastGestureTimeMs < GESTURE_DEBOUNCE_MS
    Log.i(
      TAG,
      "dispatchGesture($gesture): debounced=$isDebounced, hasListener=${onGlassesGestureListener != null}",
    )
    if (isDebounced) return
    lastGestureTimeMs = now
    onGlassesGestureListener?.invoke(gesture)
  }

  @VisibleForTesting
  fun triggerGestureForTesting(gesture: GlassesGesture) {
    onGlassesGestureListener?.invoke(gesture)
  }

  @VisibleForTesting
  fun triggerTapForTesting() {
    triggerGestureForTesting(GlassesGesture.TAP)
  }

  /** Starts [GlassesTapActivity] on the glasses' projected display so it can receive taps. */
  private fun launchGlassesTapActivity() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
      Log.w(TAG, "Glasses taps need API 35+ (sdk=${Build.VERSION.SDK_INT})")
      return
    }
    if (GlassesTapActivity.isRunning()) {
      Log.i(TAG, "GlassesTapActivity is already running; ensuring display stays awake")
      GlassesTapActivity.ensureDisplayAwakeIfRunning()
      return
    }
    try {
      val launchContext = currentHostActivity?.get() ?: context
      val glassesCtx = getGlassesContext()
      val options = ProjectedContext.createProjectedActivityOptions(glassesCtx ?: launchContext)
      val intent =
        Intent(launchContext, GlassesTapActivity::class.java).apply {
          addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
          putExtra(EXTRA_WAKE_SCREEN, true)
          if (launchContext !is Activity) {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
          }
        }
      launchContext.startActivity(intent, options.toBundle())
      Log.i(
        TAG,
        "Launched GlassesTapActivity on glasses (viaActivity=${launchContext is Activity}, " +
          "glassesDeviceId=${glassesCtx?.deviceId}, launchDisplayId=${options.launchDisplayId})",
      )
    } catch (e: Exception) {
      Log.w(TAG, "Failed to launch GlassesTapActivity on the glasses", e)
    }
  }

  // -----------------------------------------------------------------------------------------------
  // Audio
  // -----------------------------------------------------------------------------------------------

  fun playEarcon(type: XrEarconType) {
    lastPlayedEarcon = type
    earcons.play(type)
  }

  /**
   * Starts capturing the glasses mic and returns a pipe of 16 kHz mono PCM16 for ML Kit
   * `AudioSource.fromPfd`. Returns null when the glasses mic can't be used; the caller should then
   * fall back to its default mic.
   */
  fun openGlassesMicStream(): ParcelFileDescriptor? {
    val glassesCtx = getGlassesContext()
    if (glassesCtx == null) {
      micStream.stop()
      Log.w(TAG, "No glasses context; ASR falls back to the default mic")
      return null
    }
    return micStream.open(glassesCtx)
  }

  suspend fun finishGlassesMicStream() {
    micStream.finishAndFlush()
  }

  fun stopGlassesMicStream() {
    micStream.stop()
  }

  /**
   * Releases resources tied to the current teacher session (camera stream, tap listener, glasses
   * mic). This manager is an app-wide singleton, so [activeInstance] stays alive for the next
   * session; connection monitoring is released separately with [stopMonitoringConnection].
   */
  fun endSession() {
    stopGlassesCameraStreaming()
    unregisterGlassesTapListener()
  }
}
