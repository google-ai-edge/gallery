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

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "ClawAgent"

/**
 * The Agent loop that ties everything together:
 *
 * 1. Read the screen via [ClawAccessibilityService]
 * 2. Build a prompt with screen context + user request
 * 3. Call the Edge Server for inference
 * 4. Parse the model's response for actions (tap, swipe, type, back, etc.)
 * 5. Execute the action
 * 6. Repeat until the task is done or model says "done"
 */
object ClawAgent {

  // Direct model references — set by NavGraph when model initializes.
  // This allows Claw to work without Edge Server being ON.
  @Volatile var activeModel: com.google.ai.edge.gallery.data.Model? = null
  @Volatile var activeModelHelper: com.google.ai.edge.gallery.runtime.LlmModelHelper? = null

  data class AgentState(
    val isRunning: Boolean = false,
    val currentTask: String = "",
    val lastAction: String = "",
    val stepCount: Int = 0,
    val messages: List<ChatMessage> = emptyList(),
  )

  data class ChatMessage(
    val role: String, // "user", "assistant", "system"
    val content: String,
  )

  private val _state = MutableStateFlow(AgentState())
  val state: StateFlow<AgentState> = _state.asStateFlow()

  private val gson = Gson()

  /** Max agent loop steps to prevent infinite loops. */
  private const val MAX_STEPS = 15
  /** Delay between steps to let UI settle after an action. */
  private const val STEP_DELAY_MS = 1500L

  private const val SYSTEM_PROMPT = """You are Claw, an AI assistant that controls an Android phone. You can see the screen and perform actions.

When the user gives you a task, analyze the current screen and decide what to do next.

Respond in one of two ways:

1. If you need to perform an action, respond with EXACTLY one JSON line:
{"action":"tap","x":540,"y":1200}
{"action":"swipe","x1":540,"y1":1500,"x2":540,"y2":500}
{"action":"type","text":"Hello"}
{"action":"back"}
{"action":"home"}
{"action":"scroll_down"}
{"action":"scroll_up"}
{"action":"done","message":"Task completed"}
{"action":"wait"}

2. If you want to talk to the user (ask a question or report status), just respond with normal text WITHOUT any JSON.

Rules:
- Only ONE action per response
- Use the element index [N] coordinates shown in the screen description
- Elements marked with * are interactive (clickable)
- After each action, you will see the updated screen
- Say {"action":"done","message":"..."} when the task is finished"""

  // ───────────────────────────────────────────────────────────────────────
  // Public API
  // ───────────────────────────────────────────────────────────────────────

  /**
   * Start the agent loop for the given user task.
   */
  suspend fun runTask(task: String, edgeServerUrl: String = "http://127.0.0.1:8888") {
    val a11y = ClawAccessibilityService.instance.value
    if (a11y == null) {
      addMessage("assistant", "Accessibility Service is not enabled. Please enable it in Settings → Accessibility → Claw.")
      return
    }

    _state.value = AgentState(
      isRunning = true,
      currentTask = task,
      messages = listOf(ChatMessage("user", task)),
    )

    try {
      agentLoop(task, a11y, edgeServerUrl)
    } catch (e: Exception) {
      Log.e(TAG, "Agent loop error", e)
      addMessage("assistant", "Error: ${e.message}")
    } finally {
      _state.value = _state.value.copy(isRunning = false)
    }
  }

  /**
   * Stop the current agent loop.
   */
  fun stop() {
    _state.value = _state.value.copy(isRunning = false)
  }

  fun clearMessages() {
    _state.value = AgentState()
  }

  // ───────────────────────────────────────────────────────────────────────
  // Agent loop
  // ───────────────────────────────────────────────────────────────────────

  private suspend fun agentLoop(task: String, a11y: ClawAccessibilityService, baseUrl: String) {
    for (step in 1..MAX_STEPS) {
      if (!_state.value.isRunning) break

      // 1. Read screen
      val screenText = withContext(Dispatchers.Main) {
        a11y.describeScreenAsText()
      }

      // 2. Build prompt (only keep last few messages to stay within 4000 tokens)
      val prompt = buildPrompt(task, screenText, step)

      // 3. Call Edge Server
      _state.value = _state.value.copy(stepCount = step)
      val response = callEdgeServer(baseUrl, prompt)
      if (response == null) {
        addMessage("assistant", "Cannot run inference. Please make sure:\n1. Edge Server is ON\n2. A model is loaded in AI Chat\nThen try again.")
        return
      }

      // 4. Parse response
      val action = parseAction(response)

      if (action != null) {
        // Execute action
        val actionDesc = executeAction(a11y, action)
        _state.value = _state.value.copy(lastAction = actionDesc)
        addMessage("assistant", "[$actionDesc]")

        // Check if done
        if (action.get("action")?.asString == "done") {
          val msg = action.get("message")?.asString ?: "Task completed"
          addMessage("assistant", msg)
          return
        }

        // Wait for UI to settle
        delay(STEP_DELAY_MS)
      } else {
        // Model responded with text (not an action) — show to user
        addMessage("assistant", response)
        // If model is just talking, stop the loop and wait for user
        return
      }
    }

    addMessage("assistant", "Reached maximum steps ($MAX_STEPS). Stopping.")
  }

  // ───────────────────────────────────────────────────────────────────────
  // Prompt builder
  // ───────────────────────────────────────────────────────────────────────

  private fun buildPrompt(task: String, screenText: String, step: Int): String {
    return buildString {
      append("<start_of_turn>system\n")
      append(SYSTEM_PROMPT)
      append("<end_of_turn>\n")
      append("<start_of_turn>user\n")
      append("Task: $task\n\n")
      append("Step $step. Current screen:\n")
      append(screenText)
      append("\n\nWhat should I do next?")
      append("<end_of_turn>\n")
      append("<start_of_turn>model\n")
    }
  }

  // ───────────────────────────────────────────────────────────────────────
  // Edge Server call — direct in-process inference (no HTTP loopback)
  // ───────────────────────────────────────────────────────────────────────

  private suspend fun callEdgeServer(baseUrl: String, prompt: String): String? {
    // Priority 1: ClawAgent's own model reference (set by NavGraph)
    val model1 = activeModel
    val helper1 = activeModelHelper
    if (model1 != null && helper1 != null && model1.instance != null) {
      Log.i(TAG, "Using ClawAgent's direct model reference")
      return directInference(helper1, model1, prompt)
    }

    // Priority 2: EdgeServerManager's model
    val server = com.google.ai.edge.gallery.edgeserver.EdgeServerManager.server
    val model2 = server?.activeModel
    val helper2 = server?.activeModelHelper
    if (model2 != null && helper2 != null && model2.instance != null) {
      Log.i(TAG, "Using EdgeServerManager's model reference")
      return directInference(helper2, model2, prompt)
    }

    // Priority 3: HTTP fallback
    Log.w(TAG, "No direct model access, falling back to HTTP at $baseUrl")
    return httpInference(baseUrl, prompt)
  }

  /**
   * Run inference directly via LlmModelHelper (same process, no HTTP).
   */
  private suspend fun directInference(
    helper: com.google.ai.edge.gallery.runtime.LlmModelHelper,
    model: com.google.ai.edge.gallery.data.Model,
    prompt: String,
  ): String? {
    return withContext(Dispatchers.IO) {
      try {
        val result = StringBuilder()
        val latch = java.util.concurrent.CountDownLatch(1)
        var errorMsg: String? = null

        helper.runInference(
          model = model,
          input = prompt,
          resultListener = { partial, isDone, _ ->
            if (partial.isNotEmpty()) result.append(partial)
            if (isDone) latch.countDown()
          },
          cleanUpListener = { latch.countDown() },
          onError = { msg -> errorMsg = msg; latch.countDown() },
        )

        val completed = latch.await(120, java.util.concurrent.TimeUnit.SECONDS)
        if (!completed) {
          Log.e(TAG, "Direct inference timed out")
          return@withContext null
        }
        if (errorMsg != null) {
          Log.e(TAG, "Direct inference error: $errorMsg")
          return@withContext null
        }
        Log.i(TAG, "Direct inference done: ${result.length} chars")
        result.toString()
      } catch (e: Exception) {
        Log.e(TAG, "Direct inference failed: ${e.message}", e)
        null
      }
    }
  }

  /**
   * Fallback: call Edge Server via HTTP.
   */
  private suspend fun httpInference(baseUrl: String, prompt: String): String? {
    return withContext(Dispatchers.IO) {
      try {
        val url = URL("$baseUrl/v1/chat/completions")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.connectTimeout = 10_000
        conn.readTimeout = 120_000
        conn.doOutput = true

        // Build a minimal request — send raw prompt as single user message
        // to minimize token overhead (no system message via API, it's in the prompt)
        val body = JsonObject().apply {
          addProperty("model", "auto")
          add("messages", com.google.gson.JsonArray().apply {
            add(JsonObject().apply {
              addProperty("role", "user")
              addProperty("content", prompt)
            })
          })
          addProperty("stream", false)
          addProperty("max_tokens", 256)
        }

        OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }

        if (conn.responseCode == 200) {
          val responseText = conn.inputStream.bufferedReader().readText()
          val json = JsonParser.parseString(responseText).asJsonObject
          val choices = json.getAsJsonArray("choices")
          if (choices != null && choices.size() > 0) {
            choices[0].asJsonObject
              .getAsJsonObject("message")
              ?.get("content")?.asString
          } else {
            Log.e(TAG, "Edge Server returned empty choices: $responseText")
            null
          }
        } else {
          val error = conn.errorStream?.bufferedReader()?.readText() ?: "HTTP ${conn.responseCode}"
          Log.e(TAG, "Edge Server HTTP error ${conn.responseCode}: $error")
          null
        }
      } catch (e: Exception) {
        Log.e(TAG, "Edge Server call failed: ${e.javaClass.simpleName}: ${e.message}", e)
        null
      }
    }
  }

  // ───────────────────────────────────────────────────────────────────────
  // Action parser
  // ───────────────────────────────────────────────────────────────────────

  /**
   * Try to extract a JSON action from the model's response.
   * Returns null if the response is plain text (not an action).
   */
  private fun parseAction(response: String): JsonObject? {
    // Look for JSON in the response — it might be wrapped in text
    val trimmed = response.trim()

    // Try direct parse
    try {
      val json = JsonParser.parseString(trimmed).asJsonObject
      if (json.has("action")) return json
    } catch (_: Exception) {}

    // Try to find JSON within the text (model might wrap it in markdown)
    val jsonPattern = Regex("""\{[^{}]*"action"[^{}]*\}""")
    val match = jsonPattern.find(trimmed) ?: return null
    return try {
      val json = JsonParser.parseString(match.value).asJsonObject
      if (json.has("action")) json else null
    } catch (_: Exception) {
      null
    }
  }

  // ───────────────────────────────────────────────────────────────────────
  // Action executor
  // ───────────────────────────────────────────────────────────────────────

  private suspend fun executeAction(a11y: ClawAccessibilityService, action: JsonObject): String {
    val type = action.get("action")?.asString ?: return "unknown action"

    return withContext(Dispatchers.Main) {
      when (type) {
        "tap" -> {
          val x = action.get("x")?.asInt ?: 0
          val y = action.get("y")?.asInt ?: 0
          a11y.tap(x, y)
          "tap($x, $y)"
        }
        "swipe" -> {
          val x1 = action.get("x1")?.asInt ?: 0
          val y1 = action.get("y1")?.asInt ?: 0
          val x2 = action.get("x2")?.asInt ?: 0
          val y2 = action.get("y2")?.asInt ?: 0
          a11y.swipe(x1, y1, x2, y2)
          "swipe($x1,$y1 → $x2,$y2)"
        }
        "type" -> {
          val text = action.get("text")?.asString ?: ""
          a11y.typeText(text)
          "type(\"$text\")"
        }
        "back" -> {
          a11y.pressBack()
          "back"
        }
        "home" -> {
          a11y.pressHome()
          "home"
        }
        "scroll_down" -> {
          // Swipe up to scroll down (center of screen)
          a11y.swipe(540, 1500, 540, 500)
          "scroll_down"
        }
        "scroll_up" -> {
          a11y.swipe(540, 500, 540, 1500)
          "scroll_up"
        }
        "wait" -> {
          delay(2000)
          "wait"
        }
        "done" -> {
          val msg = action.get("message")?.asString ?: "done"
          "done: $msg"
        }
        else -> "unknown: $type"
      }
    }
  }

  // ───────────────────────────────────────────────────────────────────────
  // Helpers
  // ───────────────────────────────────────────────────────────────────────

  private fun addMessage(role: String, content: String) {
    val msgs = _state.value.messages + ChatMessage(role, content)
    _state.value = _state.value.copy(messages = msgs)
  }
}
