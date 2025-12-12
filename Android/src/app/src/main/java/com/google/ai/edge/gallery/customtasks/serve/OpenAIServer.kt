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

package com.google.ai.edge.gallery.customtasks.serve

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

class OpenAIServer(
    private val port: Int,
    private val modelManagerViewModel: ModelManagerViewModel,
    private val scope: CoroutineScope,
    private val serveTaskViewModel: ServeTaskViewModel
) : NanoHTTPD(port) {

    private val TAG = "OpenAIServer"

    override fun serve(session: IHTTPSession): Response {
        if (session.method == Method.POST && session.uri == "/v1/chat/completions") {
            return handleChatCompletion(session)
        }
        if (session.method == Method.GET && session.uri == "/v1/models") {
            return handleModels(session)
        }
        return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
    }

    private fun handleModels(session: IHTTPSession): Response {
        val models = JSONArray()
        val model = JSONObject()
        model.put("id", modelManagerViewModel.uiState.value.selectedModel.name)
        model.put("object", "model")
        model.put("created", System.currentTimeMillis() / 1000)
        model.put("owned_by", "user")
        models.put(model)

        val response = JSONObject()
        response.put("object", "list")
        response.put("data", models)

        return newFixedLengthResponse(Response.Status.OK, "application/json", response.toString())
    }

    private fun handleChatCompletion(session: IHTTPSession): Response {
        val map = HashMap<String, String>()
        try {
            session.parseBody(map)
        } catch (e: IOException) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Internal Error: " + e.message)
        } catch (e: ResponseException) {
            return newFixedLengthResponse(e.status, MIME_PLAINTEXT, e.message)
        }

        val jsonString = map["postData"]
        if (jsonString == null) {
             return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing body")
        }

        try {
            val jsonBody = JSONObject(jsonString)
            val messages = jsonBody.getJSONArray("messages")
            val modelName = jsonBody.optString("model", "default-model")

            // Extract the last user message. Simple implementation.
            // A real implementation would handle conversation history.
            // The LiteRT LLM API generally handles history if we keep the same instance,
            // but here we might just want to take the last prompt or try to reconstruct context.
            // For now, let's just take the last message content.

            var prompt = ""
            for (i in 0 until messages.length()) {
                val message = messages.getJSONObject(i)
                if (message.getString("role") == "user") {
                    prompt = message.getString("content")
                }
            }

            // We need to run the inference. Since serve is called on a worker thread by NanoHTTPD,
            // we can block here or use a latch. But we need to call suspend function generateResponse
            // from ModelManagerViewModel.

            var responseContent = ""
            var error: String? = null

            // Blocking call to get response
            val job = scope.launch(Dispatchers.IO) {
               try {
                   // Ensure model is selected
                   val model = modelManagerViewModel.uiState.value.selectedModel
                   if (model.name.isEmpty()) {
                       error = "No model selected"
                       return@launch
                   }

                   // We use the ServeTaskViewModel to generate response as it has access to the internal LlmModelInstance logic
                   // tailored for string return.
                   // Note: `generateResponse` in ServeTaskViewModel is suspending.

                   // Note: `generateResponse` implementation in ServeTaskViewModel assumes `message.toString()` is delta.
                   // If it is full text, we might get duplication. But for now we stick with append.

                   responseContent = serveTaskViewModel.generateResponse(model, prompt) { partial ->
                       // We could optionally log partial results or stream them if we supported streaming
                   }

               } catch (e: Exception) {
                   error = e.message
                   Log.e(TAG, "Inference error", e)
               }
            }

            // Wait for job to complete.
            while (!job.isCompleted) {
                Thread.sleep(100)
            }

            if (error != null) {
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error: $error")
            }

            val responseId = "chatcmpl-" + UUID.randomUUID().toString()
            val created = System.currentTimeMillis() / 1000

            val choice = JSONObject()
            choice.put("index", 0)
            val message = JSONObject()
            message.put("role", "assistant")
            message.put("content", responseContent)
            choice.put("message", message)
            choice.put("finish_reason", "stop")

            val choices = JSONArray()
            choices.put(choice)

            val response = JSONObject()
            response.put("id", responseId)
            response.put("object", "chat.completion")
            response.put("created", created)
            response.put("model", modelName)
            response.put("choices", choices)

             // Usage stats (fake for now)
            val usage = JSONObject()
            usage.put("prompt_tokens", prompt.length / 4) // Rough estimate
            usage.put("completion_tokens", responseContent.length / 4)
            usage.put("total_tokens", (prompt.length + responseContent.length) / 4)
            response.put("usage", usage)

            return newFixedLengthResponse(Response.Status.OK, "application/json", response.toString())

        } catch (e: Exception) {
            Log.e(TAG, "Error parsing request", e)
             return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Bad Request: " + e.message)
        }
    }
}
