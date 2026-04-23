/*
 * Copyright 2025 Google LLC
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

package com.google.ai.edge.gallery.claw

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "ClawA11y"

/**
 * Accessibility Service that provides two key capabilities:
 *
 * 1. **Read**: Dump the current screen's UI tree as a compact text description
 *    that fits within a small LLM's context window (~300-500 tokens).
 *
 * 2. **Act**: Execute tap, swipe, back, home, and text input gestures
 *    programmatically via [dispatchGesture] and [performGlobalAction].
 *
 * The service registers itself as a singleton via [instance] so the Agent
 * loop can call it directly.
 */
class ClawAccessibilityService : AccessibilityService() {

  companion object {
    private val _instance = MutableStateFlow<ClawAccessibilityService?>(null)
    val instance: StateFlow<ClawAccessibilityService?> = _instance.asStateFlow()

    /** Max UI elements to include in the screen description. */
    private const val MAX_ELEMENTS = 40
  }

  override fun onServiceConnected() {
    super.onServiceConnected()
    _instance.value = this
    Log.i(TAG, "Claw Accessibility Service connected")
  }

  override fun onDestroy() {
    _instance.value = null
    Log.i(TAG, "Claw Accessibility Service destroyed")
    super.onDestroy()
  }

  override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    // We don't need to react to events — we poll on demand.
  }

  override fun onInterrupt() {
    Log.w(TAG, "Accessibility service interrupted")
  }

  // ───────────────────────────────────────────────────────────────────────
  // READ: Dump screen UI tree
  // ───────────────────────────────────────────────────────────────────────

  /**
   * Describes the current screen as a compact string suitable for an LLM prompt.
   *
   * Format: one line per interactive element:
   * ```
   * [button "Send" at 540,1200 size 200x80]
   * [edittext "Type message" at 270,1100 size 800x60]
   * [text "Hello world" at 100,300]
   * ```
   *
   * Returns the app package name + list of visible elements.
   */
  fun describeScreen(): ScreenDescription {
    val root = rootInActiveWindow ?: return ScreenDescription("unknown", emptyList())
    val elements = mutableListOf<UiElement>()
    collectElements(root, elements)
    root.recycle()

    // Limit to MAX_ELEMENTS most relevant (interactive first, then visible text)
    val sorted = elements.sortedWith(
      compareByDescending<UiElement> { it.interactive }
        .thenByDescending { it.text.isNotEmpty() }
    ).take(MAX_ELEMENTS)

    val pkg = root.packageName?.toString() ?: "unknown"
    return ScreenDescription(pkg, sorted)
  }

  /**
   * Formats the screen description as a compact string for the LLM.
   */
  fun describeScreenAsText(): String {
    val desc = describeScreen()
    val sb = StringBuilder()
    sb.appendLine("App: ${desc.packageName}")
    sb.appendLine("Screen elements:")
    for ((i, el) in desc.elements.withIndex()) {
      val type = el.className.substringAfterLast(".")
      val text = if (el.text.isNotEmpty()) " \"${el.text.take(50)}\"" else ""
      val desc2 = if (el.contentDescription.isNotEmpty() && el.contentDescription != el.text)
        " (${el.contentDescription.take(30)})" else ""
      val interactable = if (el.interactive) " *" else ""
      sb.appendLine("  [$i] ${type}${text}${desc2} at ${el.bounds.centerX()},${el.bounds.centerY()}$interactable")
    }
    return sb.toString()
  }

  private fun collectElements(node: AccessibilityNodeInfo, out: MutableList<UiElement>, depth: Int = 0) {
    if (depth > 15) return // Safety limit

    val bounds = Rect()
    node.getBoundsInScreen(bounds)

    // Skip invisible or zero-size elements
    if (bounds.width() <= 0 || bounds.height() <= 0) return
    if (!node.isVisibleToUser) return

    val text = node.text?.toString()?.trim() ?: ""
    val contentDesc = node.contentDescription?.toString()?.trim() ?: ""
    val className = node.className?.toString() ?: ""
    val interactive = node.isClickable || node.isCheckable || node.isEditable

    // Only include elements that have text, content description, or are interactive
    if (text.isNotEmpty() || contentDesc.isNotEmpty() || interactive) {
      out.add(UiElement(
        className = className,
        text = text,
        contentDescription = contentDesc,
        bounds = bounds,
        interactive = interactive,
        isEditable = node.isEditable,
        isChecked = node.isChecked,
        isScrollable = node.isScrollable,
      ))
    }

    // Recurse into children
    for (i in 0 until node.childCount) {
      val child = node.getChild(i) ?: continue
      collectElements(child, out, depth + 1)
      child.recycle()
    }
  }

  // ───────────────────────────────────────────────────────────────────────
  // ACT: Execute gestures
  // ───────────────────────────────────────────────────────────────────────

  /**
   * Tap at the given screen coordinates.
   */
  fun tap(x: Int, y: Int, callback: ((Boolean) -> Unit)? = null) {
    val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
    val gesture = GestureDescription.Builder()
      .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
      .build()
    dispatchGesture(gesture, object : GestureResultCallback() {
      override fun onCompleted(gestureDescription: GestureDescription?) {
        Log.d(TAG, "Tap completed at $x,$y")
        callback?.invoke(true)
      }
      override fun onCancelled(gestureDescription: GestureDescription?) {
        Log.w(TAG, "Tap cancelled at $x,$y")
        callback?.invoke(false)
      }
    }, null)
  }

  /**
   * Swipe from (x1,y1) to (x2,y2).
   */
  fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 300, callback: ((Boolean) -> Unit)? = null) {
    val path = Path().apply {
      moveTo(x1.toFloat(), y1.toFloat())
      lineTo(x2.toFloat(), y2.toFloat())
    }
    val gesture = GestureDescription.Builder()
      .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
      .build()
    dispatchGesture(gesture, object : GestureResultCallback() {
      override fun onCompleted(gestureDescription: GestureDescription?) {
        callback?.invoke(true)
      }
      override fun onCancelled(gestureDescription: GestureDescription?) {
        callback?.invoke(false)
      }
    }, null)
  }

  /**
   * Press the Back button.
   */
  fun pressBack() {
    performGlobalAction(GLOBAL_ACTION_BACK)
  }

  /**
   * Press the Home button.
   */
  fun pressHome() {
    performGlobalAction(GLOBAL_ACTION_HOME)
  }

  /**
   * Press the Recent Apps button.
   */
  fun pressRecents() {
    performGlobalAction(GLOBAL_ACTION_RECENTS)
  }

  /**
   * Type text into the currently focused editable field.
   * First finds a focused editable node, then sets its text.
   */
  fun typeText(text: String): Boolean {
    val root = rootInActiveWindow ?: return false
    val focused = findFocusedEditable(root)
    if (focused != null) {
      val args = android.os.Bundle().apply {
        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
      }
      val result = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
      focused.recycle()
      root.recycle()
      return result
    }
    root.recycle()
    return false
  }

  private fun findFocusedEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
    if (node.isFocused && node.isEditable) return AccessibilityNodeInfo.obtain(node)
    for (i in 0 until node.childCount) {
      val child = node.getChild(i) ?: continue
      val result = findFocusedEditable(child)
      child.recycle()
      if (result != null) return result
    }
    return null
  }
}

// ───────────────────────────────────────────────────────────────────────
// Data classes
// ───────────────────────────────────────────────────────────────────────

data class ScreenDescription(
  val packageName: String,
  val elements: List<UiElement>,
)

data class UiElement(
  val className: String,
  val text: String,
  val contentDescription: String,
  val bounds: Rect,
  val interactive: Boolean,
  val isEditable: Boolean = false,
  val isChecked: Boolean = false,
  val isScrollable: Boolean = false,
)
