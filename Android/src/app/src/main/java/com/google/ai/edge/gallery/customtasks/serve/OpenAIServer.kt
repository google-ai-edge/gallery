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

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.IOException
import java.io.OutputStreamWriter
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.TimeUnit
import java.io.InputStream

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
        var jsonString: String? = null
        try {
            // Check Content-Length
            val contentLengthStr = session.headers["content-length"]
            if (contentLengthStr != null) {
                val contentLength = contentLengthStr.toInt()
                if (contentLength > 0) {
                    val buffer = ByteArray(contentLength)
                    var bytesRead = 0
                    while (bytesRead < contentLength) {
                        val count = session.inputStream.read(buffer, bytesRead, contentLength - bytesRead)
                        if (count == -1) break
                        bytesRead += count
                    }
                    jsonString = String(buffer, 0, bytesRead, Charsets.UTF_8)
                }
            } else {
                // Fallback to parseBody if content-length is missing or invalid
                val map = HashMap<String, String>()
                session.parseBody(map)
                jsonString = map["postData"]
            }
        } catch (e: Exception) {
             return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Internal Error: " + e.message)
        }

        if (jsonString == null) {
             return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing body")
        }

        try {
            val jsonBody = JSONObject(jsonString)
            val messages = jsonBody.getJSONArray("messages")
            val modelName = jsonBody.optString("model", "default-model")
            val isStream = jsonBody.optBoolean("stream", false)
            val maxTokens = jsonBody.optInt("max_tokens", -1).takeIf { it > 0 } ?: jsonBody.optInt("max_completion_tokens", -1)

            // Extract the last user message. Simple implementation.
            // A real implementation would handle conversation history.
            // The LiteRT LLM API generally handles history if we keep the same instance,
            // but here we might just want to take the last prompt or try to reconstruct context.
            // For now, let's just take the last message content.

            var prompt = ""
            val images = mutableListOf<Bitmap>()

            // Only process the last user message to avoid duplicating context in the stateful backend
            var lastUserMessageIndex = -1
            for (i in messages.length() - 1 downTo 0) {
                val message = messages.getJSONObject(i)
                if (message.getString("role") == "user") {
                    lastUserMessageIndex = i
                    break
                }
            }

            if (lastUserMessageIndex != -1) {
                val message = messages.getJSONObject(lastUserMessageIndex)
                val content = message.get("content")
                if (content is String) {
                    prompt = content
                } else if (content is JSONArray) {
                    for (j in 0 until content.length()) {
                        val item = content.getJSONObject(j)
                        val type = item.getString("type")
                        if (type == "text") {
                            prompt += item.getString("text")
                        } else if (type == "image_url") {
                            val imageUrl = item.getJSONObject("image_url").getString("url")
                            if (imageUrl.startsWith("data:image")) {
                                val base64Image = imageUrl.substringAfter("base64,")
                                val decodedString = Base64.decode(base64Image, Base64.DEFAULT)
                                val bitmap = BitmapFactory.decodeByteArray(decodedString, 0, decodedString.size)
                                if (bitmap != null) {
                                    images.add(bitmap)
                                }
                            }
                        }
                    }
                }
            }

            // Ensure model is selected
            val model = modelManagerViewModel.uiState.value.selectedModel
            if (model.name.isEmpty()) {
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error: No model selected")
            }

            val responseId = "chatcmpl-" + UUID.randomUUID().toString()
            val created = System.currentTimeMillis() / 1000

            if (isStream) {
                // Use BlockingQueue instead of PipedInputStream to avoid "Write end dead" issues
                // and threading complexity.
                val queue = ArrayBlockingQueue<ByteArray>(100)
                val POISON_PILL = ByteArray(0)

                scope.launch(Dispatchers.IO) {
                    try {
                        serveTaskViewModel.generateResponse(model, prompt, images, maxTokens) { partialText ->
                            val chunk = createOpenAIChunk(responseId, created, modelName, partialText)
                            val data = "data: $chunk\n\n".toByteArray(Charsets.UTF_8)
                            queue.put(data)
                        }
                        queue.put("data: [DONE]\n\n".toByteArray(Charsets.UTF_8))
                    } catch (e: Exception) {
                        Log.e(TAG, "Streaming error", e)
                        try {
                            val errorChunk = JSONObject()
                            errorChunk.put("error", e.message)
                            queue.put("data: $errorChunk\n\n".toByteArray(Charsets.UTF_8))
                        } catch (ign: Exception) {}
                    } finally {
                        try {
                            queue.put(POISON_PILL)
                        } catch (e: InterruptedException) {
                            Thread.currentThread().interrupt()
                        }
                    }
                }

                val inputStream = object : InputStream() {
                    private var buffer: ByteArray? = null
                    private var pos = 0

                    override fun read(): Int {
                        val b = ByteArray(1)
                        if (read(b, 0, 1) == -1) return -1
                        return b[0].toInt() and 0xFF
                    }

                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        if (buffer == null || pos >= buffer!!.size) {
                            try {
                                val data = queue.poll(30, TimeUnit.SECONDS)
                                if (data == null) {
                                    // Timeout
                                    return -1
                                }
                                if (data.isEmpty()) {
                                    // Poison pill / EOF
                                    return -1
                                }
                                buffer = data
                                pos = 0
                            } catch (e: InterruptedException) {
                                return -1
                            }
                        }

                        if (buffer == null) return -1

                        val available = buffer!!.size - pos
                        val count = Math.min(len, available)
                        System.arraycopy(buffer!!, pos, b, off, count)
                        pos += count
                        return count
                    }
                }

                val response = newChunkedResponse(Response.Status.OK, "text/event-stream", inputStream)
                response.addHeader("Cache-Control", "no-cache")
                return response
            } else {
                // Non-streaming
                var responseContent = ""
                var error: String? = null

                // Blocking call to get response
                val job = scope.launch(Dispatchers.IO) {
                   try {
                       responseContent = serveTaskViewModel.generateResponse(model, prompt, images, maxTokens) { partial ->
                           // Ignore partial results
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
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error parsing request", e)
             return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Bad Request: " + e.message)
        }
    }

    private fun createOpenAIChunk(id: String, created: Long, model: String, content: String): String {
        val choice = JSONObject()
        choice.put("index", 0)
        val delta = JSONObject()
        delta.put("content", content)
        choice.put("delta", delta)
        choice.put("finish_reason", JSONObject.NULL)

        val choices = JSONArray()
        choices.put(choice)

        val chunk = JSONObject()
        chunk.put("id", id)
        chunk.put("object", "chat.completion.chunk")
        chunk.put("created", created)
        chunk.put("model", model)
        chunk.put("choices", choices)

        return chunk.toString()
    }
}
