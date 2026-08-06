/*
 * Copyright 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.ai.edge.gallery.eval

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

class EvalServer(
  val port: Int,
  val context: Context,
  private val serverScope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
) {
  private var server: MiniHttpServer? = null
  private val modelManager = ModelManager(context, serverScope)
  private val conversationManager = ConversationManager()

  fun start() {
    server = MiniHttpServer(port) { request -> handleRequest(request) }
    server?.start()
    Log.i(TAG, "EvalServer started on port $port")
  }

  fun stop() {
    server?.stop()
    server = null
    Log.i(TAG, "EvalServer stopped")
  }

  fun preInitModel(
    modelPath: String,
    supportImage: Boolean,
    supportAudio: Boolean,
    accelerator: String,
  ) {
    modelManager.preInitModel(modelPath, supportImage, supportAudio, accelerator)
  }

  private fun handleRequest(request: MiniHttpServer.Request): MiniHttpServer.Response {
    val uri = request.path
    val method = request.method
    Log.i(TAG, "Request: $method $uri")

    return when {
      uri == "/health" && method == "GET" -> {
        MiniHttpServer.Response(MiniHttpServer.Status.OK, "text/plain", "OK")
      }
      uri == "/v1/chat/completions" && method == "POST" -> {
        handleChatCompletions(request)
      }
      uri.startsWith("/v1/components/") && uri.endsWith("/execute") && method == "POST" -> {
        handleComponentExecute(uri, request)
      }
      else -> {
        MiniHttpServer.Response(MiniHttpServer.Status.NOT_FOUND, "text/plain", "Not Found")
      }
    }
  }

  private fun handleChatCompletions(request: MiniHttpServer.Request): MiniHttpServer.Response {
    val json = request.body
    if (json.isEmpty()) {
      return MiniHttpServer.Response(
        MiniHttpServer.Status.BAD_REQUEST,
        "text/plain",
        "Missing body",
      )
    }

    val modelName: String
    val historyMessages = mutableListOf<HistoryMessage>()
    val prompt: String
    val promptContentStr: String
    val supportImage: Boolean
    val supportAudio: Boolean
    val accelerator: String
    val parsed: PromptParser.ParsedPrompt
    try {
      val jsonObject = JSONObject(json)
      modelName = jsonObject.getString("model")
      val messagesArray = jsonObject.getJSONArray("messages")

      for (i in 0 until messagesArray.length() - 1) {
        val msgObj = messagesArray.getJSONObject(i)
        val contentVal = msgObj.get("content")
        val contentStr = contentVal.toString()
        historyMessages.add(HistoryMessage(role = msgObj.getString("role"), content = contentStr))
      }

      val lastMsgObj = messagesArray.getJSONObject(messagesArray.length() - 1)
      if (lastMsgObj.getString("role") == "user") {
        val contentVal = lastMsgObj.get("content")
        promptContentStr = contentVal.toString()
        parsed = PromptParser.parseContent(contentVal)
        prompt = parsed.text
      } else {
        return MiniHttpServer.Response(
          MiniHttpServer.Status.BAD_REQUEST,
          "text/plain",
          "Last message must be from user",
        )
      }

      supportImage = jsonObject.optBoolean("support_image", false)
      supportAudio = jsonObject.optBoolean("support_audio", false)
      accelerator = jsonObject.optString("accelerator", "CPU")
    } catch (e: Exception) {
      return MiniHttpServer.Response(
        MiniHttpServer.Status.BAD_REQUEST,
        "text/plain",
        "Invalid JSON: ${e.message}",
      )
    }
    val images = parsed.images
    val audioClips = parsed.audioClips

    val helper =
      modelManager.getHelperForModelName(modelName)
        ?: return MiniHttpServer.Response(
          MiniHttpServer.Status.BAD_REQUEST,
          "text/plain",
          "Cannot resolve runtime for model: $modelName",
        )

    val model =
      runBlocking {
        modelManager.getOrInitModel(modelName, helper, supportImage, supportAudio, accelerator)
      }
        ?: return MiniHttpServer.Response(
          MiniHttpServer.Status.INTERNAL_ERROR,
          "text/plain",
          "Failed to initialize model: $modelName",
        )

    synchronized(model) {
      conversationManager.checkHistoryAndReset(modelName, historyMessages, model, helper)

      val resultDeferred = CompletableDeferred<String>()
      val accumulatedResult = StringBuilder()
      var finalResult = ""
      val scope = CoroutineScope(serverScope.coroutineContext + Job())

      Log.i(
        TAG,
        "Running inference for model $modelName (prompt length: ${prompt.length}, images: ${images.size}, audio: ${audioClips.size})",
      )

      runBlocking {
        helper.runInference(
          model = model,
          input = prompt,
          images = images,
          audioClips = audioClips,
          resultListener = { partialResult, done, _ ->
            Log.d(TAG, "resultListener: partialResult='$partialResult', done=$done")
            if (partialResult.isNotEmpty()) {
              accumulatedResult.append(partialResult)
            }
            if (done) {
              resultDeferred.complete(accumulatedResult.toString())
            }
          },
          cleanUpListener = {},
          onError = { error -> resultDeferred.completeExceptionally(IllegalStateException(error)) },
          coroutineScope = scope,
        )
        try {
          finalResult = resultDeferred.await()
        } catch (e: Exception) {
          Log.e(TAG, "Inference failed", e)
        }
      }

      if (resultDeferred.isCompleted && !resultDeferred.isCancelled) {
        conversationManager.appendTurn(modelName, promptContentStr, finalResult)

        val responseJson =
          try {
            val responseObj = JSONObject()
            val choicesArray = JSONArray()
            val choiceObj = JSONObject()
            choiceObj.put("index", 0)
            val messageObj = JSONObject()
            messageObj.put("role", "assistant")
            messageObj.put("content", finalResult)
            choiceObj.put("message", messageObj)
            choiceObj.put("finish_reason", "stop")
            choicesArray.put(choiceObj)
            responseObj.put("choices", choicesArray)
            responseObj.toString()
          } catch (e: Exception) {
            return MiniHttpServer.Response(
              MiniHttpServer.Status.INTERNAL_ERROR,
              "text/plain",
              "Failed to build response: ${e.message}",
            )
          }
        return MiniHttpServer.Response(MiniHttpServer.Status.OK, "application/json", responseJson)
      } else {
        return MiniHttpServer.Response(
          MiniHttpServer.Status.INTERNAL_ERROR,
          "text/plain",
          "Inference failed or timed out",
        )
      }
    }
  }

  private fun handleComponentExecute(
    uri: String,
    request: MiniHttpServer.Request,
  ): MiniHttpServer.Response {
    val parts = uri.split("/")
    if (parts.size < 4) {
      return MiniHttpServer.Response(MiniHttpServer.Status.BAD_REQUEST, "text/plain", "Invalid URI")
    }
    val componentId = parts[3]

    val json = request.body
    if (json.isEmpty()) {
      return MiniHttpServer.Response(
        MiniHttpServer.Status.BAD_REQUEST,
        "text/plain",
        "Missing body",
      )
    }

    Log.i(TAG, "Execute component $componentId")

    val modelName: String
    val prompt: String
    val supportImage: Boolean
    val supportAudio: Boolean
    val accelerator: String
    try {
      val jsonObject = JSONObject(json)
      modelName = jsonObject.optString("model", "")
      prompt = jsonObject.optString("prompt", "")
      supportImage = jsonObject.optBoolean("support_image", false)
      supportAudio = jsonObject.optBoolean("support_audio", false)
      accelerator = jsonObject.optString("accelerator", "CPU")
    } catch (e: Exception) {
      return MiniHttpServer.Response(
        MiniHttpServer.Status.BAD_REQUEST,
        "text/plain",
        "Invalid JSON: ${e.message}",
      )
    }

    val helper =
      modelManager.getHelperForModelName(modelName)
        ?: return MiniHttpServer.Response(
          MiniHttpServer.Status.BAD_REQUEST,
          "text/plain",
          "Cannot resolve runtime for model: $modelName",
        )

    val model =
      runBlocking {
        modelManager.getOrInitModel(modelName, helper, supportImage, supportAudio, accelerator)
      }
        ?: return MiniHttpServer.Response(
          MiniHttpServer.Status.INTERNAL_ERROR,
          "text/plain",
          "Failed to initialize model: $modelName",
        )

    val resultDeferred = CompletableDeferred<String>()
    val accumulatedResult = StringBuilder()
    var finalResult = ""
    val scope = CoroutineScope(serverScope.coroutineContext + Job())

    runBlocking {
      helper.runInference(
        model = model,
        input = prompt,
        resultListener = { partialResult, done, _ ->
          Log.d(TAG, "resultListener: partialResult='$partialResult', done=$done")
          if (partialResult.isNotEmpty()) {
            accumulatedResult.append(partialResult)
          }
          if (done) {
            resultDeferred.complete(accumulatedResult.toString())
          }
        },
        cleanUpListener = {},
        onError = { error -> resultDeferred.completeExceptionally(IllegalStateException(error)) },
        coroutineScope = scope,
      )
      try {
        finalResult = resultDeferred.await()
      } catch (e: Exception) {
        Log.e(TAG, "Inference failed", e)
      }
    }

    if (resultDeferred.isCompleted && !resultDeferred.isCancelled) {
      val responseJson = JSONObject().apply { put("output", finalResult) }.toString()
      return MiniHttpServer.Response(MiniHttpServer.Status.OK, "application/json", responseJson)
    } else {
      return MiniHttpServer.Response(
        MiniHttpServer.Status.INTERNAL_ERROR,
        "text/plain",
        "Execution failed",
      )
    }
  }

  companion object {
    private const val TAG = "EvalServer"
  }
}
