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
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.runtime.CleanUpListener
import com.google.ai.edge.gallery.runtime.LlmModelHelper
import com.google.ai.edge.gallery.runtime.ResultListener
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.ToolProvider
import com.google.common.truth.Truth.assertThat
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.util.ReflectionHelpers

/**
 * Unit tests for [EvalServer] endpoints, verifying health checks and error handling for chat
 * completions.
 */
@RunWith(RobolectricTestRunner::class)
class EvalServerTest {

  private lateinit var evalServer: EvalServer
  private lateinit var context: Context
  private val port = 8082

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    evalServer = EvalServer(port, context)
    evalServer.start()
  }

  @After
  fun tearDown() {
    evalServer.stop()
  }

  @Test
  fun healthEndpoint_returnsOk() {
    val url = URL("http://localhost:$port/health")
    val connection = url.openConnection() as HttpURLConnection
    connection.requestMethod = "GET"

    assertThat(connection.responseCode).isEqualTo(200)
    val response = BufferedReader(InputStreamReader(connection.inputStream)).readText()
    assertThat(response).isEqualTo("OK")
  }

  @Test
  fun chatCompletionsEndpoint_missingBody_returnsBadRequest() {
    val url = URL("http://localhost:$port/v1/chat/completions")
    val connection = url.openConnection() as HttpURLConnection
    connection.requestMethod = "POST"
    connection.doOutput = true
    // Sending no body

    assertThat(connection.responseCode).isEqualTo(400)
    val errorStream = connection.errorStream
    val response = BufferedReader(InputStreamReader(errorStream)).readText()
    assertThat(response).isEqualTo("Missing body")
  }

  @Test
  fun chatCompletionsEndpoint_invalidJson_returnsBadRequest() {
    val url = URL("http://localhost:$port/v1/chat/completions")
    val connection = url.openConnection() as HttpURLConnection
    connection.requestMethod = "POST"
    connection.doOutput = true

    connection.outputStream.write("{ invalid json }".toByteArray())

    assertThat(connection.responseCode).isEqualTo(400)
    val response = BufferedReader(InputStreamReader(connection.errorStream)).readText()
    assertThat(response).startsWith("Invalid JSON:")
  }

  @Test
  fun chatCompletionsEndpoint_validSmokeTestAgent_returnsOk() {
    // Inject Mock ModelManager
    val field = EvalServer::class.java.getDeclaredField("modelManager")
    field.isAccessible = true
    val fakeManager =
      object : ModelManager(context) {
        override fun getHelperForModelName(
          modelName: String
        ): com.google.ai.edge.gallery.runtime.LlmModelHelper {
          return ModelManagerTest.FakeLlmModelHelper()
        }

        override fun resolveModel(
          modelName: String,
          supportImage: Boolean,
          supportAudio: Boolean,
          accelerator: String,
        ): com.google.ai.edge.gallery.data.Model {
          return com.google.ai.edge.gallery.data.Model(
            name = "test",
            runtimeType = com.google.ai.edge.gallery.data.RuntimeType.AICORE,
            isLlm = true,
          )
        }
      }
    field.set(evalServer, fakeManager)

    // Send valid JSON request for chat completions
    val url = URL("http://localhost:$port/v1/chat/completions")
    val connection = url.openConnection() as HttpURLConnection
    connection.requestMethod = "POST"
    connection.setRequestProperty("Content-Type", "application/json")
    connection.doOutput = true

    val jsonPayload =
      """
      {
        "model": "test-model",
        "task_type": "smoke_test_agent",
        "skills": ["mood-tracker"],
        "mock_tool_results": {
          "run_js": {"result": "ok"}
        },
        "messages": [
          {"role": "user", "content": "Hello"}
        ]
      }
      """
        .trimIndent()
    connection.outputStream.write(jsonPayload.toByteArray())

    assertThat(connection.responseCode).isEqualTo(200)
    val response = BufferedReader(InputStreamReader(connection.inputStream)).readText()
    assertThat(response).contains("choices")
  }

  @Test
  fun chatCompletionsEndpoint_initFails_returnsInternalError() {
    val url = URL("http://localhost:$port/v1/chat/completions")
    val connection = url.openConnection() as HttpURLConnection
    connection.requestMethod = "POST"
    connection.doOutput = true

    val json =
      JSONObject()
        .apply {
          put("model", "unknown-model")
          put(
            "messages",
            JSONArray().apply {
              put(
                JSONObject().apply {
                  put("role", "user")
                  put("content", "hello")
                }
              )
            },
          )
        }
        .toString()
    connection.outputStream.write(json.toByteArray())

    assertThat(connection.responseCode).isEqualTo(400) // Cannot resolve runtime
  }

  @Test
  fun chatCompletionsEndpoint_agentChat_success() {
    // Inject fake model manager
    val fakeManager =
      object : ModelManager(context) {
        override fun getHelperForModelName(modelName: String): LlmModelHelper? =
          FakeLlmModelHelper()

        override suspend fun getOrInitModel(
          modelName: String,
          helper: LlmModelHelper,
          supportImage: Boolean,
          supportAudio: Boolean,
          accelerator: String,
        ): Model? =
          Model(
            name = modelName,
            runtimeType = com.google.ai.edge.gallery.data.RuntimeType.LITERT_LM,
            isLlm = true,
          )
      }
    ReflectionHelpers.setField(evalServer, "modelManager", fakeManager)

    val url = URL("http://localhost:$port/v1/chat/completions")
    val connection = url.openConnection() as HttpURLConnection
    connection.requestMethod = "POST"
    connection.doOutput = true

    val json =
      JSONObject()
        .apply {
          put("model", "test-model")
          put("task_type", "llm_agent_chat")
          put("skills", JSONArray().apply { put("mood-tracker") })
          put(
            "mock_tool_results",
            JSONObject().apply { put("run_js", JSONObject().apply { put("result", "ok") }) },
          )
          put(
            "messages",
            JSONArray().apply {
              put(
                JSONObject().apply {
                  put("role", "user")
                  put("content", "hello")
                }
              )
            },
          )
        }
        .toString()
    connection.outputStream.write(json.toByteArray())

    // To hit active tool update in EvalServer, we send the same exact request again so
    // isHistoryMatch is true
    val url2 = URL("http://localhost:$port/v1/chat/completions")
    val connection2 = url2.openConnection() as HttpURLConnection
    connection2.requestMethod = "POST"
    connection2.doOutput = true
    connection2.outputStream.write(json.toByteArray())

    assertThat(connection2.responseCode).isEqualTo(200)
  }

  class FakeLlmModelHelper : LlmModelHelper {
    override fun initialize(
      c: Context,
      m: Model,
      t: String,
      si: Boolean,
      sa: Boolean,
      onDone: (String) -> Unit,
      sys: Contents?,
      tools: List<ToolProvider>,
      e: Boolean,
      scope: CoroutineScope?,
    ) {}

    override fun resetConversation(
      m: Model,
      si: Boolean,
      sa: Boolean,
      sys: Contents?,
      tools: List<ToolProvider>,
      e: Boolean,
      initMsg: List<Message>,
    ) {}

    override fun cleanUp(m: Model, onDone: () -> Unit) {}

    override fun runInference(
      m: Model,
      p: String,
      r: ResultListener,
      cl: CleanUpListener,
      err: (String) -> Unit,
      img: List<Bitmap>,
      aud: List<ByteArray>,
      scope: CoroutineScope?,
      ctx: Map<String, String>?,
    ) {
      r("Success", true, null)
    }

    override fun stopResponse(m: Model) {}
  }
}
