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

import android.app.PendingIntent
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.core.app.PendingIntentCompat
import androidx.lifecycle.lifecycleScope
import androidx.xr.projected.ProjectedActivityCompat
import androidx.xr.projected.experimental.ExperimentalProjectedApi
import java.lang.ref.WeakReference
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Projected activity launched onto the glasses device
 * (`android.hardware.display.category.XR_PROJECTED`) to receive physical touchpad input:
 * - **Single tap** (`DOWN` -> `UP` within [TOUCHPAD_TAP_SLOP_PX], or `KEYCODE_DPAD_CENTER` /
 *   `KEYCODE_ENTER`): dispatches [GlassesGesture.TAP] (scan new object / submit utterance).
 * - **Swipe forward** (`dx >= TOUCHPAD_SWIPE_MIN_DISTANCE_PX` with `abs(dx) > abs(dy)`, or
 *   `KEYCODE_DPAD_RIGHT`): dispatches [GlassesGesture.SWIPE_FORWARD] (ask follow-up question).
 * - **Unrecognized gestures** (such as vertical volume swipes or backward swipes): buffered
 *   `ACTION_DOWN`/`ACTION_MOVE` events and the terminating `ACTION_UP` are replayed in order to
 *   `super` so system or view handlers receive the complete stroke.
 */
@OptIn(ExperimentalProjectedApi::class)
class GlassesTapActivity : ComponentActivity() {

  private var projectedActivityCompat: ProjectedActivityCompat? = null
  private val inputReceiverMutex = Mutex()
  private var inputSurfaceView: View? = null
  @Volatile private var isActivityResumed = false
  private var isCleared = false
  private var trackingGesture = false
  private var downX = 0f
  private var downY = 0f
  private var minX = 0f
  private var minXAtY = 0f
  private var maxDisplacementPx = 0f
  private var maxDisplacementY = 0f
  private val bufferedStrokeEvents = mutableListOf<MotionEvent>()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    currentInstance = WeakReference(this)
    val surface =
      View(this).apply {
        setBackgroundColor(Color.BLACK)
        isFocusable = true
        isFocusableInTouchMode = true
      }
    inputSurfaceView = surface
    setContentView(surface)
    surface.requestFocus()
    lifecycleScope.launch { registerPendingIntentAsInputReceiver() }
    Log.i(TAG, "onCreate: displayId=${safeDisplayId()}, deviceId=${currentDeviceId()}")
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    inputSurfaceView?.requestFocus()
    lifecycleScope.launch { registerPendingIntentAsInputReceiver() }
  }

  override fun onResume() {
    super.onResume()
    isActivityResumed = true
    inputSurfaceView?.requestFocus()
    Log.i(
      TAG,
      "onResume: displayId=${safeDisplayId()}, deviceId=${currentDeviceId()}, hasWindowFocus=${hasWindowFocus()}",
    )
    lifecycleScope.launch { registerPendingIntentAsInputReceiver() }
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

  /**
   * Returns the ID of the projected display this activity is attached to, or `null` if detached.
   */
  private fun safeDisplayId(): Int? = runCatching { display?.displayId }.getOrNull()

  /** Returns the virtual device ID associated with this activity on API 34+, or `-1` otherwise. */
  private fun currentDeviceId(): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) deviceId else -1

  override fun onWindowFocusChanged(hasFocus: Boolean) {
    super.onWindowFocusChanged(hasFocus)
    if (hasFocus) {
      inputSurfaceView?.requestFocus()
    }
    Log.i(TAG, "onWindowFocusChanged: hasFocus=$hasFocus, displayId=${safeDisplayId()}")
  }

  /**
   * Registers this projected activity with [ProjectedActivityCompat.setActivityAsInputReceiver] so
   * touchpad gestures on glasses route to this activity.
   */
  private suspend fun registerPendingIntentAsInputReceiver() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
    inputReceiverMutex.withLock {
      if (isCleared || isFinishing) return
      try {
        val compat =
          projectedActivityCompat ?: ProjectedActivityCompat.create(this@GlassesTapActivity)
        if (isCleared) {
          compat.clearActivityAsInputReceiver()
          compat.close()
          return
        }
        projectedActivityCompat = compat
        val intent =
          Intent(this@GlassesTapActivity, GlassesTapActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        val pendingIntent =
          PendingIntentCompat.getActivity(
            this@GlassesTapActivity,
            /* requestCode= */ 0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT,
            /* isMutable= */ false,
          ) ?: return@withLock
        compat.setActivityAsInputReceiver(pendingIntent)
        Log.i(TAG, "Registered GlassesTapActivity via setActivityAsInputReceiver")
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.w(TAG, "Failed to register as the projected input receiver", e)
      }
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
    return handlePointerMotion(
      event = event,
      channel = "genericMotion",
      dispatchToSuper = { super.dispatchGenericMotionEvent(it) },
    )
  }

  override fun dispatchTouchEvent(event: MotionEvent): Boolean {
    return handlePointerMotion(
      event = event,
      channel = "touch",
      dispatchToSuper = { super.dispatchTouchEvent(it) },
    )
  }

  override fun dispatchKeyEvent(event: KeyEvent): Boolean {
    Log.d(
      TAG,
      "dispatchKeyEvent: action=${event.action}, keyCode=${event.keyCode}, repeat=${event.repeatCount}",
    )
    when (event.action) {
      KeyEvent.ACTION_UP -> {
        if (event.repeatCount == 0) {
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
        }
      }
      KeyEvent.ACTION_DOWN -> {
        when (event.keyCode) {
          KeyEvent.KEYCODE_DPAD_CENTER,
          KeyEvent.KEYCODE_ENTER,
          KeyEvent.KEYCODE_NUMPAD_ENTER,
          KeyEvent.KEYCODE_DPAD_RIGHT -> return true
          else -> {}
        }
      }
    }
    return super.dispatchKeyEvent(event)
  }

  /**
   * Tracks a single-pointer touchpad motion sequence (`ACTION_DOWN` -> `ACTION_MOVE` ->
   * `ACTION_UP`) and classifies it as [GlassesGesture.TAP] or [GlassesGesture.SWIPE_FORWARD].
   *
   * Also tracks the minimum X coordinate reached during the stroke (`minX`) so that a slight
   * initial backward finger roll at touchdown does not reduce the measured forward swipe distance,
   * while still requiring horizontal dominance over overall vertical travel (`abs(dy)` and
   * [maxDisplacementY]).
   *
   * When a completed stroke is not classified as [GlassesGesture.TAP] or
   * [GlassesGesture.SWIPE_FORWARD] (for example, a vertical volume swipe or backward swipe), all
   * buffered `ACTION_DOWN` and `ACTION_MOVE` events from the stroke plus the terminating
   * `ACTION_UP` are replayed in order to [dispatchToSuper].
   */
  private fun handlePointerMotion(
    event: MotionEvent,
    channel: String,
    dispatchToSuper: (MotionEvent) -> Boolean,
  ): Boolean {
    when (event.actionMasked) {
      MotionEvent.ACTION_DOWN -> {
        clearBufferedStrokeEvents()
        bufferedStrokeEvents.add(MotionEvent.obtain(event))
        trackingGesture = true
        downX = event.x
        downY = event.y
        minX = event.x
        minXAtY = event.y
        maxDisplacementPx = 0f
        maxDisplacementY = 0f
        Log.i(
          TAG,
          "$channel ACTION_DOWN at (${event.x}, ${event.y}) source=0x${event.source.toString(16)}",
        )
        return true
      }
      MotionEvent.ACTION_MOVE -> {
        if (!trackingGesture) return dispatchToSuper(event)
        bufferedStrokeEvents.add(MotionEvent.obtain(event))
        val stepDy = abs(event.y - downY)
        maxDisplacementPx = maxOf(maxDisplacementPx, abs(event.x - downX), stepDy)
        maxDisplacementY = maxOf(maxDisplacementY, stepDy)
        if (event.x < minX) {
          minX = event.x
          minXAtY = event.y
        }
        return true
      }
      MotionEvent.ACTION_CANCEL -> {
        trackingGesture = false
        clearBufferedStrokeEvents()
        Log.i(TAG, "$channel ACTION_CANCEL")
        return true
      }
      MotionEvent.ACTION_UP -> {
        if (!trackingGesture) return dispatchToSuper(event)
        trackingGesture = false
        val dx = event.x - downX
        val dy = event.y - downY
        maxDisplacementY = maxOf(maxDisplacementY, abs(dy))
        val forwardStrokeDx = event.x - minX
        val forwardStrokeDy = event.y - minXAtY
        val isSwipeForward =
          (dx >= TOUCHPAD_SWIPE_MIN_DISTANCE_PX && abs(dx) > abs(dy)) ||
            (dx > 0f &&
              forwardStrokeDx >= TOUCHPAD_SWIPE_MIN_DISTANCE_PX &&
              abs(forwardStrokeDx) > abs(forwardStrokeDy) &&
              abs(forwardStrokeDx) > abs(dy) &&
              abs(forwardStrokeDx) > maxDisplacementY)
        val gesture =
          when {
            isSwipeForward -> GlassesGesture.SWIPE_FORWARD
            maxDisplacementPx <= TOUCHPAD_TAP_SLOP_PX &&
              abs(dx) <= TOUCHPAD_TAP_SLOP_PX &&
              abs(dy) <= TOUCHPAD_TAP_SLOP_PX -> GlassesGesture.TAP
            else -> null
          }
        Log.i(
          TAG,
          "$channel ACTION_UP dx=$dx dy=$dy forwardDx=$forwardStrokeDx maxDisp=$maxDisplacementPx " +
            "tapSlop=$TOUCHPAD_TAP_SLOP_PX swipeMin=$TOUCHPAD_SWIPE_MIN_DISTANCE_PX -> gesture=$gesture",
        )
        if (gesture != null) {
          clearBufferedStrokeEvents()
          forwardGesture(gesture)
          return true
        }
        return replayBufferedStrokeToSuper(event, dispatchToSuper)
      }
      else -> return dispatchToSuper(event)
    }
  }

  /**
   * Replays buffered `ACTION_DOWN` and `ACTION_MOVE` events to [dispatchToSuper] (recycling each
   * buffered copy), then dispatches the terminating [upEvent] and returns whether any event in the
   * replayed stroke was handled by the superclass.
   */
  private fun replayBufferedStrokeToSuper(
    upEvent: MotionEvent,
    dispatchToSuper: (MotionEvent) -> Boolean,
  ): Boolean {
    var handled = false
    try {
      for (buffered in bufferedStrokeEvents) {
        if (dispatchToSuper(buffered)) {
          handled = true
        }
      }
    } finally {
      clearBufferedStrokeEvents()
    }
    if (dispatchToSuper(upEvent)) {
      handled = true
    }
    return handled
  }

  /** Recycles and clears any [MotionEvent] copies buffered for the current in-progress stroke. */
  private fun clearBufferedStrokeEvents() {
    for (buffered in bufferedStrokeEvents) {
      buffered.recycle()
    }
    bufferedStrokeEvents.clear()
  }

  /** Forwards a recognized [gesture] to the active [XrGlassesManager] singleton. */
  private fun forwardGesture(gesture: GlassesGesture) {
    val manager = XrGlassesManager.activeInstance
    if (manager == null) {
      Log.w(TAG, "Glasses gesture $gesture dropped: no XrGlassesManager")
      return
    }
    manager.dispatchGesture(gesture)
  }

  override fun onDestroy() {
    Log.i(TAG, "onDestroy: finishing=$isFinishing")
    isActivityResumed = false
    isCleared = true
    clearBufferedStrokeEvents()
    inputSurfaceView = null
    projectedActivityCompat?.let { compat ->
      try {
        compat.clearActivityAsInputReceiver()
        compat.close()
      } catch (e: Exception) {
        Log.w(TAG, "Failed to clear the projected input receiver", e)
      }
    }
    projectedActivityCompat = null
    if (currentInstance?.get() === this) {
      currentInstance = null
    }
    super.onDestroy()
  }

  companion object {
    private const val TAG = "AGGlassesTapActivity"

    /**
     * Maximum pointer displacement (in raw glasses touchpad coordinates) still classified as a
     * single [GlassesGesture.TAP].
     *
     * Sized just below [TOUCHPAD_SWIPE_MIN_DISTANCE_PX] so there is no deadzone between a slight
     * finger roll during a tap (`<= 26px`) and a forward swipe (`>= 27px`), while avoiding the host
     * phone's high-DPI `ViewConfiguration.scaledTouchSlop` (`51px`), which would swallow short
     * swipes on the glasses touchpad.
     */
    private const val TOUCHPAD_TAP_SLOP_PX = 26f

    /**
     * Minimum forward horizontal displacement (in raw glasses touchpad coordinates) classified as a
     * [GlassesGesture.SWIPE_FORWARD], matching `androidx.xr.glimmer`'s `GlassesGestureDetector`
     * (`21px * 1.3 = 27px`).
     */
    private const val TOUCHPAD_SWIPE_MIN_DISTANCE_PX = 27f

    @Volatile private var currentInstance: WeakReference<GlassesTapActivity>? = null

    /** Finishes the glasses-side activity, if running. */
    fun finishIfRunning() {
      currentInstance?.get()?.finish()
      currentInstance = null
    }

    /** Whether the glasses-side activity is currently alive, resumed, and has window focus. */
    fun isFocusedAndResumed(): Boolean {
      val instance = currentInstance?.get() ?: return false
      return !instance.isFinishing && instance.isActivityResumed && instance.hasWindowFocus()
    }

    /** Whether the glasses-side activity is currently alive and resumed. */
    fun isRunning(): Boolean {
      val instance = currentInstance?.get() ?: return false
      return !instance.isFinishing && instance.isActivityResumed
    }
  }
}
