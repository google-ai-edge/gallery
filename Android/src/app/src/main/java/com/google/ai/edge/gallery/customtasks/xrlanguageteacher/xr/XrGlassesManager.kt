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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Touchpad gestures recognized on the projected glasses activity. */
enum class GlassesGesture {
  /** Single tap on the glasses touchpad (scan new object / submit utterance). */
  TAP,
  /** Horizontal forward swipe on the glasses touchpad (ask follow-up question). */
  SWIPE_FORWARD,
}

/**
 * Manages the app's use of glasses in XR Language Teacher.
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
  /** Secondary constructor used by unit tests to supply a custom [ioDispatcher]. */
  @VisibleForTesting
  constructor(
    context: Context,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
  ) : this(context, CoroutineScope(SupervisorJob() + ioDispatcher), ioDispatcher)

  companion object {
    private const val TAG = "AGXrGlassesManager"

    /** Gestures that arrive within this window of the previous gesture are ignored. */
    private const val GESTURE_DEBOUNCE_MS = 300L

    /** The live manager, used by [GlassesTapActivity] to forward touchpad gestures. */
    @SuppressLint("StaticFieldLeak") @Volatile var activeInstance: XrGlassesManager? = null
  }

  /**
   * Explicit host-bound context so system services and host executors remain associated with the
   * phone even when `@ApplicationContext`'s `deviceId` mutates after a projected activity runs.
   */
  private val hostContext: Context =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      runCatching { ProjectedContext.createHostDeviceContext(context) }.getOrDefault(context)
    } else {
      context
    }

  private val mainHandler = Handler(Looper.getMainLooper())
  /**
   * Single-lane dispatcher so earcon `preload`, `play`, and `release` calls run in strict FIFO
   * order and a `playEarcon` launched right before `endSession()` never races after `release()`.
   */
  private val earconDispatcher = ioDispatcher.limitedParallelism(1)
  private val audioManager = hostContext.getSystemService(AudioManager::class.java)
  private val virtualDeviceManager =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      hostContext.getSystemService(VirtualDeviceManager::class.java)
    } else {
      null
    }

  private val cameraStream = GlassesCameraStream(hostContext)
  private val micStream = GlassesMicStream(applicationScope, ioDispatcher)
  private val earcons = GlassesEarcons(hostContext)

  private val _isGlassesConnected = MutableStateFlow(false)
  /** Whether a projected glasses device is currently connected to the host phone. */
  val isGlassesConnected: StateFlow<Boolean> = _isGlassesConnected.asStateFlow()

  /** Latest upright frame from the glasses camera, or null when it isn't streaming. */
  val glassesCameraBitmap: StateFlow<Bitmap?> = cameraStream.frames

  /** When non-null, replaces the detected connection state. Set only by tests. */
  private var connectedOverrideForTesting: Boolean? = null

  /** Overrides the glasses connection state in unit tests. */
  @VisibleForTesting
  fun setGlassesConnectedForTesting(connected: Boolean) {
    connectedOverrideForTesting = connected
    _isGlassesConnected.value = connected
  }

  /** Injects a synthetic glasses camera frame in unit tests. */
  @VisibleForTesting
  fun setGlassesCameraBitmapForTesting(bitmap: Bitmap?) {
    cameraStream.setFrameForTesting(bitmap)
  }

  /** Most recent earcon requested via [playEarcon], exposed for unit test assertions. */
  @VisibleForTesting
  var lastPlayedEarcon: XrEarconType? = null
    private set

  /** Most recently active host phone activity, used as the launch context for projected intents. */
  @Volatile private var currentHostActivity: WeakReference<Activity>? = null
  private var lastGestureTimeMs = -GESTURE_DEBOUNCE_MS
  private var onGlassesGestureListener: ((GlassesGesture) -> Unit)? = null

  private var audioDeviceCallback: AudioDeviceCallback? = null
  private var virtualDeviceListener: VirtualDeviceManager.VirtualDeviceListener? = null
  private var projectedConnectionJob: Job? = null
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

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
      if (projectedConnectionJob?.isActive != true) {
        projectedConnectionJob = applicationScope.launch {
          try {
            ProjectedContext.isProjectedDeviceConnected(hostContext, ioDispatcher).collect {
              connected ->
              _isGlassesConnected.value = connectedOverrideForTesting ?: connected
            }
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            Log.w(TAG, "ProjectedContext.isProjectedDeviceConnected flow failed; falling back", e)
            mainHandler.post {
              if (connectionMonitorCount > 0) {
                registerFallbackConnectionListeners()
              }
            }
          }
        }
      }
      return
    }

    registerFallbackConnectionListeners()
  }

  /**
   * Registers [AudioDeviceCallback] and [VirtualDeviceManager.VirtualDeviceListener] as a fallback
   * on pre-API 36 devices or when [ProjectedContext.isProjectedDeviceConnected] is unavailable.
   */
  @MainThread
  private fun registerFallbackConnectionListeners() {
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
        ContextCompat.getMainExecutor(hostContext),
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

    projectedConnectionJob?.cancel()
    projectedConnectionJob = null
    audioDeviceCallback?.let { audioManager?.unregisterAudioDeviceCallback(it) }
    audioDeviceCallback = null
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
      virtualDeviceListener?.let { virtualDeviceManager?.unregisterVirtualDeviceListener(it) }
    }
    virtualDeviceListener = null
  }

  /** Refreshes [isGlassesConnected] by probing whether a projected device context is available. */
  fun updateGlassesConnectionState() {
    _isGlassesConnected.value = connectedOverrideForTesting ?: (getGlassesContext() != null)
  }

  /** Returns a context for the connected glasses (projected) device, or null if none. */
  fun getGlassesContext(): Context? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
    return try {
      ProjectedContext.createProjectedDeviceContext(hostContext)
    } catch (_: Exception) {
      null
    }
  }

  /**
   * Returns the projected device ID and any permissions from [permissions] not yet granted on the
   * connected glasses virtual device, or `null` if no glasses device permission prompt is needed.
   */
  internal fun checkMissingGlassesPermissions(
    permissions: List<String>
  ): GlassesPermissionRequest? {
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

  /** Starts streaming the glasses camera into [glassesCameraBitmap] and preloads earcon assets. */
  fun startGlassesCameraStreaming() {
    val glassesCtx = getGlassesContext()
    if (glassesCtx == null) {
      Log.w(TAG, "Cannot start the glasses camera: no glasses context")
      return
    }
    applicationScope.launch(earconDispatcher) { earcons.preload(glassesCtx) }
    cameraStream.start(glassesCtx)
  }

  /** Stops streaming the glasses camera and recycles any buffered preview frames. */
  fun stopGlassesCameraStreaming() {
    cameraStream.stop()
  }

  // -----------------------------------------------------------------------------------------------
  // Gestures
  // -----------------------------------------------------------------------------------------------

  /**
   * Sets [listener] as the target for glasses touchpad gestures (replacing any previous listener)
   * and launches [GlassesTapActivity] on the glasses if it isn't running and focused yet.
   */
  fun registerGlassesGestureListener(listener: (GlassesGesture) -> Unit) {
    onGlassesGestureListener = listener
    Log.i(
      TAG,
      "registerGlassesGestureListener: tapActivityRunning=${GlassesTapActivity.isFocusedAndResumed()}, " +
        "hasHostActivity=${currentHostActivity?.get() != null}",
    )
    launchGlassesTapActivity()
  }

  /**
   * Clears the active gesture listener, stops the glasses microphone capture, and finishes
   * [GlassesTapActivity] if it is running.
   */
  fun unregisterGlassesTapListener() {
    onGlassesGestureListener = null
    stopGlassesMicStream()
    GlassesTapActivity.finishIfRunning()
  }

  /**
   * Called by [GlassesTapActivity] for each recognized touchpad gesture, applying a
   * [GESTURE_DEBOUNCE_MS] debounce window before invoking [onGlassesGestureListener].
   */
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

  /** Directly invokes the registered gesture listener in unit tests without debouncing. */
  @VisibleForTesting
  fun triggerGestureForTesting(gesture: GlassesGesture) {
    onGlassesGestureListener?.invoke(gesture)
  }

  /** Starts [GlassesTapActivity] on the glasses' projected display so it can receive taps. */
  private fun launchGlassesTapActivity() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
      Log.w(TAG, "Glasses taps need API 35+ (sdk=${Build.VERSION.SDK_INT})")
      return
    }
    if (GlassesTapActivity.isFocusedAndResumed()) {
      Log.i(TAG, "GlassesTapActivity is already focused and resumed")
      return
    }
    try {
      val launchContext = currentHostActivity?.get() ?: hostContext
      val glassesCtx = getGlassesContext()
      val options = ProjectedContext.createProjectedActivityOptions(glassesCtx ?: launchContext)
      val intent =
        Intent(launchContext, GlassesTapActivity::class.java).apply {
          addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
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

  /** Plays the standardized glasses earcon [type] on the glasses audio output. */
  fun playEarcon(type: XrEarconType) {
    lastPlayedEarcon = type
    val glassesCtx = getGlassesContext()
    applicationScope.launch(earconDispatcher) { earcons.play(type, glassesCtx) }
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

  /**
   * Stops hardware microphone capture on the glasses while flushing any buffered PCM samples into
   * the ASR pipe before closing it.
   */
  suspend fun finishGlassesMicStream() {
    micStream.finishAndFlush()
  }

  /** Immediately aborts any active glasses microphone capture and closes its pipe. */
  fun stopGlassesMicStream() {
    micStream.stop()
  }

  /**
   * Releases resources tied to the current teacher session (camera stream, tap listener, glasses
   * mic, earcon SoundPool). This manager is an app-wide singleton, so [activeInstance] stays alive
   * for the next session; connection monitoring is released separately with
   * [stopMonitoringConnection].
   */
  fun endSession() {
    stopGlassesCameraStreaming()
    unregisterGlassesTapListener()
    applicationScope.launch(earconDispatcher) { earcons.release() }
  }
}
