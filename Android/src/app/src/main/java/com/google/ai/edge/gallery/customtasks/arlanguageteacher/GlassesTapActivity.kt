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

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import androidx.xr.projected.ProjectedActivityCompat
import androidx.xr.projected.ProjectedDisplayController
import androidx.xr.projected.ProjectedDisplayController.PresentationMode
import androidx.xr.projected.ProjectedDisplayController.PresentationModeFlags
import androidx.xr.projected.experimental.ExperimentalProjectedApi
import com.google.android.libraries.security.app.SaferPendingIntent
import java.lang.ref.WeakReference
import java.util.function.Consumer
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Projected activity launched onto the glasses device (`xr_projected`) to receive physical touchpad
 * input across both display-equipped glasses (e.g. Betty) and displayless audio glasses (e.g.
 * Jinju):
 * - **Single tap** (`DOWN` -> `UP` within tap slop, or `KEYCODE_DPAD_CENTER` / `KEYCODE_ENTER`):
 *   dispatches [GlassesGesture.TAP] (scan new object / submit utterance).
 * - **Swipe forward** (`dx > scaledTouchSlop * SWIPE_SLOP_MULTIPLIER` with `abs(dx) > abs(dy)`, or
 *   `KEYCODE_DPAD_RIGHT`): dispatches [GlassesGesture.SWIPE_FORWARD] (ask follow-up question).
 */
@OptIn(ExperimentalProjectedApi::class)
class GlassesTapActivity : ComponentActivity() {

  private var projectedActivityCompat: ProjectedActivityCompat? = null
  private var projectedDisplayController: ProjectedDisplayController? = null
  private var inputSurfaceView: View? = null
  private var keepAliveJob: Job? = null
  @Volatile private var displayServiceBinder: IBinder? = null
  @Volatile private var isDisplayServiceBound = false
  @Volatile private var lastVisualsOn = false
  @Volatile private var isActivityResumed = false
  private var isCleared = false
  private var trackingGesture = false
  private var downX = 0f
  private var downY = 0f
  private var maxDisplacementPx = 0f

  private val presentationModeListener =
    Consumer<PresentationModeFlags> { flags ->
      val visualsOn = flags.hasPresentationMode(PresentationMode.VISUALS_ON)
      lastVisualsOn = visualsOn
      Log.i(TAG, "onPresentationModeChanged: visualsOn=$visualsOn, displayId=${safeDisplayId()}")
      if (isCleared || isFinishing) return@Consumer
      if (visualsOn) {
        reapplyKeepScreenOnAndInputReceiver()
      } else {
        requestTurnOnGlassesScreen(reason = "visualsOn=false")
      }
    }

  private val serviceCallbackBinder =
    object : Binder() {
      init {
        attachInterface(null, CALLBACK_DESCRIPTOR)
      }

      override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code in IBinder.FIRST_CALL_TRANSACTION..IBinder.LAST_CALL_TRANSACTION) {
          data.enforceInterface(CALLBACK_DESCRIPTOR)
        }
        return when (code) {
          CALLBACK_TRANSACTION_ON_SERVICE_RESOLVED -> {
            val name = data.readString()
            val binder = data.readStrongBinder()
            Log.i(TAG, "Resolved glasses service '$name' (hasBinder=${binder != null})")
            if (name == DISPLAY_SERVICE_NAME && binder != null) {
              displayServiceBinder = binder
              if (!isCleared && !isFinishing) {
                requestTurnOnGlassesScreen(reason = "serviceResolved")
              }
            }
            true
          }
          CALLBACK_TRANSACTION_ON_ERROR -> {
            val errorCode = data.readInt()
            Log.w(TAG, "GlassesServiceBroker failed to resolve Display service: code=$errorCode")
            true
          }
          else -> super.onTransact(code, data, reply, flags)
        }
      }
    }

  private val displayServiceConnection =
    object : ServiceConnection {
      override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        Log.i(TAG, "Connected to GlassesServiceBroker: $name (hasBinder=${service != null})")
        if (service != null && !isCleared && !isFinishing) {
          queryDisplayServiceFromBroker(service)
        }
      }

      override fun onServiceDisconnected(name: ComponentName?) {
        Log.i(TAG, "Disconnected from GlassesServiceBroker: $name")
        displayServiceBinder = null
      }
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    currentInstance = WeakReference(this)
    setShowWhenLocked(true)
    setTurnScreenOn(true)
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    val surface =
      View(this).apply {
        setBackgroundColor(Color.BLACK)
        isFocusable = true
        isFocusableInTouchMode = true
      }
    inputSurfaceView = surface
    setContentView(surface)
    surface.requestFocus()
    onBackPressedDispatcher.addCallback(
      this,
      object : OnBackPressedCallback(enabled = true) {
        override fun handleOnBackPressed() {
          Log.d(TAG, "Swallowed system Back press on GlassesTapActivity")
        }
      },
    )
    ensureGlassesDisplayServiceBound()
    Log.i(TAG, "onCreate: displayId=${safeDisplayId()}, deviceId=${currentDeviceId()}")
  }

  override fun onResume() {
    super.onResume()
    isActivityResumed = true
    inputSurfaceView?.requestFocus()
    Log.i(
      TAG,
      "onResume: displayId=${safeDisplayId()}, deviceId=${currentDeviceId()}, hasWindowFocus=${hasWindowFocus()}",
    )
    requestTurnOnGlassesScreen(reason = "onResume")
    ensureRegisteredAsInputReceiver()
    startScreenKeepAliveLoop()
  }

  override fun onPause() {
    isActivityResumed = false
    Log.i(TAG, "onPause: displayId=${safeDisplayId()}, hasWindowFocus=${hasWindowFocus()}")
    super.onPause()
  }

  override fun onStop() {
    Log.i(TAG, "onStop: displayId=${safeDisplayId()}")
    super.onStop()
  }

  private fun safeDisplayId(): Int? = runCatching { display?.displayId }.getOrNull()

  private fun currentDeviceId(): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) deviceId else -1

  override fun onWindowFocusChanged(hasFocus: Boolean) {
    super.onWindowFocusChanged(hasFocus)
    if (hasFocus) {
      inputSurfaceView?.requestFocus()
    }
    Log.i(TAG, "onWindowFocusChanged: hasFocus=$hasFocus, displayId=${safeDisplayId()}")
  }

  private fun ensureGlassesDisplayServiceBound() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
    if (isDisplayServiceBound || isCleared || isFinishing) return
    try {
      val bindIntent = Intent(ACTION_BIND_V2_API).setPackage(GLASSES_CORE_PACKAGE)
      val bound =
        applicationContext.bindService(
          bindIntent,
          Context.BIND_AUTO_CREATE or Context.BIND_INCLUDE_CAPABILITIES,
          mainExecutor,
          displayServiceConnection,
        )
      isDisplayServiceBound = bound
      Log.i(TAG, "Bound GlassesServiceBroker ($ACTION_BIND_V2_API): bound=$bound")
    } catch (e: Exception) {
      Log.w(TAG, "Unable to bind GlassesServiceBroker", e)
    }
  }

  private fun queryDisplayServiceFromBroker(brokerBinder: IBinder) {
    val data = Parcel.obtain()
    try {
      data.writeInterfaceToken(BROKER_DESCRIPTOR)
      data.writeString(DISPLAY_SERVICE_NAME)
      data.writeTypedObject(Bundle.EMPTY, 0)
      data.writeStrongBinder(serviceCallbackBinder)
      val ok =
        brokerBinder.transact(BROKER_TRANSACTION_GET_SERVICE_ASYNC, data, null, IBinder.FLAG_ONEWAY)
      Log.i(TAG, "Requested Display service from GlassesServiceBroker (ok=$ok)")
    } catch (e: Exception) {
      Log.w(TAG, "Failed to request Display service from GlassesServiceBroker", e)
    } finally {
      data.recycle()
    }
  }

  /**
   * Programmatically wakes display-equipped glasses (e.g. Betty) out of `SCREEN_OFF` via
   * `IDisplayProjectedService.turnOnScreen(DEFAULT)` so touchpad gestures work without requiring
   * physical wake button presses.
   */
  private fun requestTurnOnGlassesScreen(reason: String) {
    val binder = displayServiceBinder
    if (binder == null) {
      ensureGlassesDisplayServiceBound()
      return
    }
    val data = Parcel.obtain()
    val reply = Parcel.obtain()
    try {
      data.writeInterfaceToken(DISPLAY_DESCRIPTOR)
      data.writeInt(DISPLAY_MODE_DEFAULT)
      val ok = binder.transact(DISPLAY_TRANSACTION_TURN_ON_SCREEN, data, reply, 0)
      if (ok) {
        reply.readException()
      }
      Log.i(TAG, "turnOnScreen(DEFAULT) sent via DisplayProjectedService (reason=$reason, ok=$ok)")
    } catch (e: Exception) {
      Log.w(TAG, "Failed calling DisplayProjectedService.turnOnScreen (reason=$reason)", e)
    } finally {
      reply.recycle()
      data.recycle()
    }
  }

  private fun reapplyKeepScreenOnAndInputReceiver() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
    lifecycleScope.launch {
      projectedDisplayController?.let { ctrl ->
        runCatching { ctrl.addLayoutParamsFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
      }
      registerPendingIntentAsInputReceiver()
    }
  }

  private fun startScreenKeepAliveLoop() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
    if (keepAliveJob?.isActive == true) return
    keepAliveJob = lifecycleScope.launch {
      while (isActive && !isCleared && !isFinishing) {
        if (!lastVisualsOn) {
          requestTurnOnGlassesScreen(reason = "keepAlive")
        }
        projectedDisplayController?.let { ctrl ->
          runCatching { ctrl.addLayoutParamsFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        }
        delay(SCREEN_KEEP_ALIVE_INTERVAL_MS)
      }
    }
  }

  /**
   * Registers this activity as the glasses input receiver and keeps the projected host window awake
   * so physical touchpad events reach [dispatchGenericMotionEvent] on both displayless audio
   * glasses and display-equipped glasses.
   */
  private fun ensureRegisteredAsInputReceiver() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
    lifecycleScope.launch {
      try {
        val displayCtrl =
          projectedDisplayController
            ?: ProjectedDisplayController.create(this@GlassesTapActivity).also { ctrl ->
              ctrl.addPresentationModeChangedListener(mainExecutor, presentationModeListener)
            }
        if (isCleared) {
          displayCtrl.removePresentationModeChangedListener(presentationModeListener)
          displayCtrl.close()
          return@launch
        }
        projectedDisplayController = displayCtrl
        displayCtrl.addLayoutParamsFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Log.i(TAG, "Configured ProjectedDisplayController with FLAG_KEEP_SCREEN_ON")
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.w(TAG, "ProjectedDisplayController unavailable on this device", e)
      }
    }
    lifecycleScope.launch { registerPendingIntentAsInputReceiver() }
  }

  private suspend fun registerPendingIntentAsInputReceiver() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
    try {
      val compat =
        projectedActivityCompat ?: ProjectedActivityCompat.create(this@GlassesTapActivity)
      if (isCleared) {
        compat.clearActivityAsInputReceiver()
        compat.close()
        return
      }
      projectedActivityCompat = compat
      val pendingIntent =
        SaferPendingIntent.getActivity(
          this@GlassesTapActivity,
          /* requestCode= */ 0,
          Intent(this@GlassesTapActivity, GlassesTapActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            .putExtra(XrGlassesManager.EXTRA_WAKE_SCREEN, true),
          PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
      if (pendingIntent != null) {
        compat.setActivityAsInputReceiver(pendingIntent)
        Log.i(TAG, "Registered GlassesTapActivity via setActivityAsInputReceiver")
      } else {
        Log.w(TAG, "SaferPendingIntent.getActivity returned null")
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.w(TAG, "Failed to register as the projected input receiver", e)
    }
  }

  override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
    if ((event.source and InputDevice.SOURCE_TOUCH_NAVIGATION) == 0) {
      Log.d(
        TAG,
        "dispatchGenericMotionEvent ignored non-touch-nav source=0x${event.source.toString(16)} action=${event.actionMasked}",
      )
      return super.dispatchGenericMotionEvent(event)
    }
    return handlePointerMotion(event, channel = "genericMotion")
      ?: super.dispatchGenericMotionEvent(event)
  }

  override fun dispatchTouchEvent(event: MotionEvent): Boolean {
    return handlePointerMotion(event, channel = "touch") ?: super.dispatchTouchEvent(event)
  }

  override fun dispatchKeyEvent(event: KeyEvent): Boolean {
    Log.d(
      TAG,
      "dispatchKeyEvent: action=${event.action}, keyCode=${event.keyCode}, repeat=${event.repeatCount}",
    )
    if (event.action == KeyEvent.ACTION_UP && event.repeatCount == 0) {
      val gesture =
        when (event.keyCode) {
          KeyEvent.KEYCODE_DPAD_CENTER,
          KeyEvent.KEYCODE_ENTER,
          KeyEvent.KEYCODE_NUMPAD_ENTER -> GlassesGesture.TAP
          KeyEvent.KEYCODE_DPAD_RIGHT -> GlassesGesture.SWIPE_FORWARD
          else -> null
        }
      if (gesture != null) {
        Log.i(TAG, "Recognized key gesture=$gesture from keyCode=${event.keyCode}")
        forwardGesture(gesture)
        return true
      }
    } else if (
      event.action == KeyEvent.ACTION_DOWN &&
        (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
          event.keyCode == KeyEvent.KEYCODE_ENTER ||
          event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
          event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
    ) {
      return true
    }
    return super.dispatchKeyEvent(event)
  }

  private fun handlePointerMotion(event: MotionEvent, channel: String): Boolean? {
    when (event.actionMasked) {
      MotionEvent.ACTION_DOWN -> {
        trackingGesture = true
        downX = event.x
        downY = event.y
        maxDisplacementPx = 0f
        Log.i(
          TAG,
          "$channel ACTION_DOWN at (${event.x}, ${event.y}) source=0x${event.source.toString(16)}",
        )
        return true
      }
      MotionEvent.ACTION_MOVE -> {
        if (trackingGesture) {
          maxDisplacementPx = maxOf(maxDisplacementPx, abs(event.x - downX), abs(event.y - downY))
        }
        return true
      }
      MotionEvent.ACTION_CANCEL -> {
        trackingGesture = false
        Log.i(TAG, "$channel ACTION_CANCEL")
        return true
      }
      MotionEvent.ACTION_UP -> {
        if (trackingGesture) {
          trackingGesture = false
          val scaledSlop = ViewConfiguration.get(this).scaledTouchSlop.toFloat()
          val tapSlopPx = maxOf(scaledSlop, DEFAULT_TAP_SLOP_PX)
          val swipeMinDistancePx = scaledSlop * SWIPE_SLOP_MULTIPLIER
          val dx = event.x - downX
          val dy = event.y - downY
          val gesture =
            when {
              maxDisplacementPx <= tapSlopPx && abs(dx) <= tapSlopPx && abs(dy) <= tapSlopPx ->
                GlassesGesture.TAP
              dx > swipeMinDistancePx && abs(dx) > abs(dy) -> GlassesGesture.SWIPE_FORWARD
              else -> null
            }
          Log.i(
            TAG,
            "$channel ACTION_UP dx=$dx dy=$dy maxDisp=$maxDisplacementPx tapSlop=$tapSlopPx swipeMin=$swipeMinDistancePx -> gesture=$gesture",
          )
          if (gesture != null) {
            forwardGesture(gesture)
          }
        }
        return true
      }
      else -> return null
    }
  }

  private fun forwardGesture(gesture: GlassesGesture) {
    val manager = XrGlassesManager.activeInstance
    if (manager == null) {
      Log.w(TAG, "Glasses gesture $gesture dropped: no XrGlassesManager")
      return
    }
    manager.dispatchGesture(gesture)
  }

  private fun unbindGlassesDisplayService() {
    displayServiceBinder = null
    if (!isDisplayServiceBound) return
    isDisplayServiceBound = false
    try {
      applicationContext.unbindService(displayServiceConnection)
    } catch (e: Exception) {
      Log.w(TAG, "Failed to unbind GlassesServiceBroker", e)
    }
  }

  override fun onDestroy() {
    Log.i(TAG, "onDestroy: finishing=$isFinishing")
    isActivityResumed = false
    isCleared = true
    keepAliveJob?.cancel()
    keepAliveJob = null
    inputSurfaceView = null
    projectedDisplayController?.let { controller ->
      try {
        controller.removeLayoutParamsFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        controller.removePresentationModeChangedListener(presentationModeListener)
        controller.close()
      } catch (e: Exception) {
        Log.w(TAG, "Failed to close ProjectedDisplayController", e)
      }
    }
    projectedDisplayController = null
    projectedActivityCompat?.let { compat ->
      try {
        compat.clearActivityAsInputReceiver()
        compat.close()
      } catch (e: Exception) {
        Log.w(TAG, "Failed to clear the projected input receiver", e)
      }
    }
    projectedActivityCompat = null
    unbindGlassesDisplayService()
    if (currentInstance?.get() === this) {
      currentInstance = null
    }
    super.onDestroy()
  }

  companion object {
    private const val TAG = "AGGlassesTapActivity"
    private const val DEFAULT_TAP_SLOP_PX = 24f
    private const val SWIPE_SLOP_MULTIPLIER = 1.3f
    private const val SCREEN_KEEP_ALIVE_INTERVAL_MS = 4_000L
    private const val GLASSES_CORE_PACKAGE = "com.google.android.glasses.core"
    private const val ACTION_BIND_V2_API = "com.google.android.glasses.core.action.BIND_V2_API"
    private const val DISPLAY_SERVICE_NAME = "Display"
    private const val BROKER_DESCRIPTOR =
      "com.google.android.glasses.sdk.broker.IGlassesServiceBroker"
    private const val BROKER_TRANSACTION_GET_SERVICE_ASYNC = IBinder.FIRST_CALL_TRANSACTION + 1
    private const val CALLBACK_DESCRIPTOR =
      "com.google.android.glasses.sdk.broker.IGlassesServiceCallback"
    private const val CALLBACK_TRANSACTION_ON_SERVICE_RESOLVED = IBinder.FIRST_CALL_TRANSACTION
    private const val CALLBACK_TRANSACTION_ON_ERROR = IBinder.FIRST_CALL_TRANSACTION + 1
    private const val DISPLAY_DESCRIPTOR =
      "com.google.android.projection.sdk.platform.display.IDisplayProjectedService"
    private const val DISPLAY_TRANSACTION_TURN_ON_SCREEN = IBinder.FIRST_CALL_TRANSACTION
    private const val DISPLAY_MODE_DEFAULT = 0

    @Volatile private var currentInstance: WeakReference<GlassesTapActivity>? = null

    /** Finishes the glasses-side activity, if running. */
    fun finishIfRunning() {
      currentInstance?.get()?.finish()
      currentInstance = null
    }

    /** Ensures the glasses display is awake and keeping screen on if an instance is active. */
    fun ensureDisplayAwakeIfRunning() {
      currentInstance?.get()?.let { instance ->
        if (!instance.isFinishing && !instance.isCleared) {
          instance.requestTurnOnGlassesScreen(reason = "ensureDisplayAwakeIfRunning")
          instance.reapplyKeepScreenOnAndInputReceiver()
        }
      }
    }

    /** Whether the glasses-side activity is currently alive and resumed. */
    fun isRunning(): Boolean {
      val instance = currentInstance?.get() ?: return false
      return !instance.isFinishing && instance.isActivityResumed
    }
  }
}
