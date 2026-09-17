# Local API Server Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let Google AI Edge Gallery expose its currently loaded on-device model over an OpenAI-compatible `/v1/chat/completions` HTTP endpoint, so ARTEMIS (or anything else) can use it as a free local LLM.

**Architecture:** A new `LocalApiForegroundService` hosts an embedded Ktor CIO server bound to `0.0.0.0:<port>`. It reads the app's existing `AgentRuntimeExecutor` singleton (used by every chat screen already) to find the currently loaded model, resets its session with the request's message history, runs one turn, and returns the full response as one JSON object. A new Preferences DataStore holds the on/off toggle, port, and bearer token; a new section in `SettingsDialog.kt` controls it.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt, Ktor (server: CIO engine + content-negotiation + kotlinx-json — client artifacts already used by the app's MCP feature), kotlinx.serialization, androidx.datastore-preferences, JUnit4 (JVM unit tests).

**Spec:** `docs/superpowers/specs/2026-09-17-local-api-server-design.md`

**Repo root for all paths below:** `Android/src/app/`

---

### Task 1: Add Ktor server dependencies

**Files:**
- Modify: `Android/src/gradle/libs.versions.toml`
- Modify: `Android/src/app/build.gradle.kts`

- [ ] **Step 1: Add the new library aliases to the version catalog**

In `Android/src/gradle/libs.versions.toml`, in the `[libraries]` section, right after the existing `ktor-client-core` line (currently line 102), add:

```toml
ktor-server-core = { group = "io.ktor", name = "ktor-server-core", version.ref = "ktor" }
ktor-server-cio = { group = "io.ktor", name = "ktor-server-cio", version.ref = "ktor" }
ktor-server-content-negotiation = { group = "io.ktor", name = "ktor-server-content-negotiation", version.ref = "ktor" }
ktor-serialization-kotlinx-json = { group = "io.ktor", name = "ktor-serialization-kotlinx-json", version.ref = "ktor" }
```

Also add the Preferences DataStore artifact right after the existing `androidx-datastore` line (currently line 68):

```toml
androidx-datastore-preferences = { group = "androidx.datastore", name = "datastore-preferences", version.ref = "dataStore" }
```

- [ ] **Step 2: Reference the new libraries from the app module**

In `Android/src/app/build.gradle.kts`, right after the existing `implementation(libs.androidx.datastore)` line (currently line 92), add:

```kotlin
  implementation(libs.androidx.datastore.preferences)
  implementation(libs.ktor.server.core)
  implementation(libs.ktor.server.cio)
  implementation(libs.ktor.server.content.negotiation)
  implementation(libs.ktor.serialization.kotlinx.json)
```

- [ ] **Step 3: Verify the project syncs and compiles**

Run: `cd Android/src && ./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL` (no source changes yet, this only proves the new dependencies resolve).

- [ ] **Step 4: Commit**

```bash
git add Android/src/gradle/libs.versions.toml Android/src/app/build.gradle.kts
git commit -m "build: add Ktor server and Preferences DataStore dependencies"
```

---

### Task 2: Expose the active model from `AgentRuntimeExecutor`

**Files:**
- Modify: `Android/src/app/src/main/java/com/google/ai/edge/gallery/agent/AgentRuntimeExecutor.kt`
- Modify: `Android/src/app/src/main/java/com/google/ai/edge/gallery/agent/DefaultAgentRuntimeExecutor.kt`
- Test (new): `Android/src/app/src/test/java/com/google/ai/edge/gallery/agent/DefaultAgentRuntimeExecutorActiveModelInfoTest.kt`

- [ ] **Step 1: Write the failing test**

Create `Android/src/app/src/test/java/com/google/ai/edge/gallery/agent/DefaultAgentRuntimeExecutorActiveModelInfoTest.kt`:

```kotlin
package com.google.ai.edge.gallery.agent

import android.graphics.Bitmap
import com.google.ai.edge.gallery.agent.sessions.LlmSessionManager
import com.google.ai.edge.gallery.agent.sessions.SessionConfig
import com.google.ai.edge.gallery.agent.sessions.generateSessionId
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.proto.ChatMessageProto
import com.google.ai.edge.gallery.proto.ChatSessionProto
import com.google.ai.edge.gallery.runtime.CleanUpListener
import com.google.ai.edge.gallery.runtime.ResultListener
import com.google.ai.edge.gallery.skills.NoOpSkillsProvider
import com.google.ai.edge.gallery.tools.RuntimeToolDispatcher
import com.google.ai.edge.gallery.tools.RuntimeToolsProvider
import com.google.ai.edge.litertlm.Message
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private class FakeLlmSessionManager : LlmSessionManager {
  override suspend fun createSession(config: SessionConfig): String = generateSessionId()

  override suspend fun loadSession(sessionId: String, config: SessionConfig): List<ChatMessageProto> =
    emptyList()

  override suspend fun resetSession(
    sessionId: String,
    config: SessionConfig,
    initialMessages: List<Message>,
    enableConversationConstrainedDecoding: Boolean,
  ) {}

  override suspend fun listSessions(taskId: String?): List<ChatSessionProto> = emptyList()

  override suspend fun deleteSession(sessionId: String) {}

  override suspend fun clearAllSessions() {}

  override suspend fun saveSessionHistory(
    sessionId: String,
    messages: List<ChatMessageProto>,
    originalModel: String?,
    taskId: String?,
  ) {}

  override suspend fun generateResponse(
    sessionId: String,
    model: Model,
    input: String,
    resultListener: ResultListener,
    cleanUpListener: CleanUpListener,
    onError: (message: String) -> Unit,
    images: List<Bitmap>,
    audioClips: List<ByteArray>,
    extraContext: Map<String, String>?,
  ) {}

  override fun stopResponse(sessionId: String, model: Model) {}

  override suspend fun linkFeedbackToSession(
    sessionId: String,
    feedbackId: String,
    messageIndex: Int?,
  ) {}
}

class DefaultAgentRuntimeExecutorActiveModelInfoTest {
  private fun newExecutor() =
    DefaultAgentRuntimeExecutor(
      skillsProvider = NoOpSkillsProvider(),
      toolsProvider = RuntimeToolsProvider(),
      toolDispatcher = RuntimeToolDispatcher(),
      llmSessionManager = FakeLlmSessionManager(),
    )

  @Test
  fun `activeModelInfo is null before any session is set`() {
    val executor = newExecutor()
    assertNull(executor.activeModelInfo)
  }

  @Test
  fun `activeModelInfo reflects the model from the last resetSession call`() = runBlocking {
    val executor = newExecutor()
    val model = Model(name = "test-model")

    executor.resetSession(
      AgentRuntimeConfig(model = model, taskId = "llm_chat", supportImage = true)
    )

    val info = executor.activeModelInfo
    assertEquals("test-model", info?.model?.name)
    assertEquals("llm_chat", info?.taskId)
    assertEquals(true, info?.supportImage)
  }

  @Test
  fun `activeModelInfo is null again after cleanUp`() = runBlocking {
    val executor = newExecutor()
    val model = Model(name = "test-model")
    executor.resetSession(AgentRuntimeConfig(model = model, taskId = "llm_chat"))

    var cleanUpCalled = false
    executor.cleanUp { cleanUpCalled = true }

    assertNull(executor.activeModelInfo)
    assertEquals(true, cleanUpCalled)
  }
}
```

- [ ] **Step 2: Run the test to verify it fails to compile**

Run: `cd Android/src && ./gradlew :app:testDebugUnitTest --tests "com.google.ai.edge.gallery.agent.DefaultAgentRuntimeExecutorActiveModelInfoTest"`
Expected: compile error — `activeModelInfo` is unresolved on `AgentRuntimeExecutor`.

- [ ] **Step 3: Add `activeModelInfo` to the interface**

In `Android/src/app/src/main/java/com/google/ai/edge/gallery/agent/AgentRuntimeExecutor.kt`, add this import:

```kotlin
import com.google.ai.edge.gallery.data.Model
```

Then, right after the existing `activeSessionId` property (after line 51, before the KDoc for `initialize`), add:

```kotlin
  /**
   * The model, task ID, and image-support flag of the currently active session, or null if no
   * session has been initialized yet (or it was cleared by [cleanUp]).
   */
  val activeModelInfo: ActiveModelInfo?
    get() = null
```

Then, after the closing brace of the `AgentRuntimeExecutor` interface (after line 133), add:

```kotlin
/**
 * Snapshot of the model backing the currently active [AgentRuntimeExecutor] session.
 *
 * @property model The model currently loaded for the active session.
 * @property taskId The task ID the active session was configured for.
 * @property supportImage Whether the active session accepts image attachments.
 */
data class ActiveModelInfo(val model: Model, val taskId: String, val supportImage: Boolean)
```

- [ ] **Step 4: Override it in `DefaultAgentRuntimeExecutor`**

In `Android/src/app/src/main/java/com/google/ai/edge/gallery/agent/DefaultAgentRuntimeExecutor.kt`, right after the existing `activeSessionId` override (after line 71, before `initialize`), add:

```kotlin
  override val activeModelInfo: ActiveModelInfo?
    get() =
      activeSession.get()?.sessionConfig?.let {
        ActiveModelInfo(model = it.model, taskId = it.taskId, supportImage = it.supportImage)
      }
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `cd Android/src && ./gradlew :app:testDebugUnitTest --tests "com.google.ai.edge.gallery.agent.DefaultAgentRuntimeExecutorActiveModelInfoTest"`
Expected: `BUILD SUCCESSFUL`, 3 tests passed.

- [ ] **Step 6: Commit**

```bash
git add Android/src/app/src/main/java/com/google/ai/edge/gallery/agent/AgentRuntimeExecutor.kt \
        Android/src/app/src/main/java/com/google/ai/edge/gallery/agent/DefaultAgentRuntimeExecutor.kt \
        Android/src/app/src/test/java/com/google/ai/edge/gallery/agent/DefaultAgentRuntimeExecutorActiveModelInfoTest.kt
git commit -m "feat(agent): expose the active session's model via activeModelInfo"
```

---

### Task 3: OpenAI-compatible request/response mapping (pure Kotlin, fully unit-testable)

**Files:**
- Create: `Android/src/app/src/main/java/com/google/ai/edge/gallery/apiserver/ChatCompletionsModels.kt`
- Create: `Android/src/app/src/main/java/com/google/ai/edge/gallery/apiserver/ChatCompletionsMapping.kt`
- Test (new): `Android/src/app/src/test/java/com/google/ai/edge/gallery/apiserver/ChatCompletionsMappingTest.kt`

This task builds the pure-function core: OpenAI JSON request → internal turn data → litertlm `Message`s + `Bitmap`s, and an `AgentResponse` → OpenAI JSON response. No Android Service, no Ktor wiring yet — that's Task 5.

- [ ] **Step 1: Write the failing tests**

Create `Android/src/app/src/test/java/com/google/ai/edge/gallery/apiserver/ChatCompletionsMappingTest.kt`:

```kotlin
package com.google.ai.edge.gallery.apiserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatCompletionsMappingTest {
  @Test
  fun `textContent extracts plain string content`() {
    val message = ChatCompletionMessage(role = "user", content = TextOrParts.Text("hello"))
    assertEquals("hello", message.textContent())
  }

  @Test
  fun `textContent concatenates text parts and ignores image parts`() {
    val message =
      ChatCompletionMessage(
        role = "user",
        content =
          TextOrParts.Parts(
            listOf(
              ContentPart.Text(text = "what is in this image?"),
              ContentPart.ImageUrl(imageUrl = ImageUrlValue(url = "data:image/png;base64,AAAA")),
            )
          ),
      )
    assertEquals("what is in this image?", message.textContent())
  }

  @Test
  fun `imageDataUris returns only the base64 payloads from image parts`() {
    val message =
      ChatCompletionMessage(
        role = "user",
        content =
          TextOrParts.Parts(
            listOf(
              ContentPart.Text(text = "describe this"),
              ContentPart.ImageUrl(imageUrl = ImageUrlValue(url = "data:image/png;base64,AAAA")),
            )
          ),
      )
    assertEquals(listOf("AAAA"), message.imageBase64Payloads())
  }

  @Test
  fun `imageDataUris is empty for a plain text message`() {
    val message = ChatCompletionMessage(role = "user", content = TextOrParts.Text("hello"))
    assertTrue(message.imageBase64Payloads().isEmpty())
  }

  @Test
  fun `toLiteRtRole maps user and assistant roles`() {
    assertEquals(
      LiteRtRole.USER,
      ChatCompletionMessage(role = "user", content = TextOrParts.Text("hi")).liteRtRole(),
    )
    assertEquals(
      LiteRtRole.MODEL,
      ChatCompletionMessage(role = "assistant", content = TextOrParts.Text("hi")).liteRtRole(),
    )
    assertNull(ChatCompletionMessage(role = "system", content = TextOrParts.Text("hi")).liteRtRole())
  }

  @Test
  fun `successResponse wraps output in the OpenAI choices shape`() {
    val json = chatCompletionSuccessJson(output = "hi there")
    assertTrue(json.contains("\"role\":\"assistant\""))
    assertTrue(json.contains("\"content\":\"hi there\""))
    assertTrue(json.contains("\"object\":\"chat.completion\""))
  }

  @Test
  fun `errorResponse wraps a message in the OpenAI error shape`() {
    val json = errorResponseJson(message = "No model loaded")
    assertTrue(json.contains("\"message\":\"No model loaded\""))
  }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd Android/src && ./gradlew :app:testDebugUnitTest --tests "com.google.ai.edge.gallery.apiserver.ChatCompletionsMappingTest"`
Expected: compile errors — none of the referenced types/functions exist yet.

- [ ] **Step 3: Create the data model types**

Create `Android/src/app/src/main/java/com/google/ai/edge/gallery/apiserver/ChatCompletionsModels.kt`:

```kotlin
package com.google.ai.edge.gallery.apiserver

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/** Request body for `POST /v1/chat/completions`, matching the OpenAI API's shape. */
@Serializable
data class ChatCompletionRequest(val model: String = "", val messages: List<ChatCompletionMessage> = emptyList())

@Serializable
data class ChatCompletionMessage(val role: String, val content: TextOrParts) {
  /** The concatenated text of this message, ignoring any image parts. */
  fun textContent(): String =
    when (content) {
      is TextOrParts.Text -> content.value
      is TextOrParts.Parts ->
        content.parts.filterIsInstance<ContentPart.Text>().joinToString(separator = " ") { it.text }
    }

  /** The base64 payload (after the `data:...;base64,` prefix) of every image part, in order. */
  fun imageBase64Payloads(): List<String> =
    when (content) {
      is TextOrParts.Text -> emptyList()
      is TextOrParts.Parts ->
        content.parts.filterIsInstance<ContentPart.ImageUrl>().mapNotNull {
          val marker = ";base64,"
          val idx = it.imageUrl.url.indexOf(marker)
          if (idx == -1) null else it.imageUrl.url.substring(idx + marker.length)
        }
    }

  /** Maps this message's OpenAI `role` to the litertlm turn role, or null for unsupported roles. */
  fun liteRtRole(): LiteRtRole? =
    when (role) {
      "user" -> LiteRtRole.USER
      "assistant" -> LiteRtRole.MODEL
      else -> null
    }
}

enum class LiteRtRole {
  USER,
  MODEL,
}

/** A message's `content` field, which OpenAI allows to be either a plain string or a parts array. */
@Serializable(with = TextOrPartsSerializer::class)
sealed interface TextOrParts {
  @Serializable data class Text(val value: String) : TextOrParts

  @Serializable data class Parts(val parts: List<ContentPart>) : TextOrParts
}

@Serializable
sealed interface ContentPart {
  @Serializable data class Text(val text: String) : ContentPart

  @Serializable data class ImageUrl(val imageUrl: ImageUrlValue) : ContentPart
}

@Serializable data class ImageUrlValue(val url: String)

object TextOrPartsSerializer : JsonContentPolymorphicSerializer<TextOrParts>(TextOrParts::class) {
  override fun selectDeserializer(element: JsonElement) =
    if (element is JsonArray) TextOrParts.Parts.serializer() else TextOrPartsText.serializer()
}

/** Internal helper so a bare JSON string deserializes into [TextOrParts.Text]. */
@Serializable
private data class TextOrPartsText(val value: String)
```

- [ ] **Step 4: Run the tests again**

Run: `cd Android/src && ./gradlew :app:testDebugUnitTest --tests "com.google.ai.edge.gallery.apiserver.ChatCompletionsMappingTest"`
Expected: still fails to compile — `chatCompletionSuccessJson` and `errorResponseJson` don't exist yet, and the `TextOrPartsSerializer` polymorphic wiring needs the response-shape file next. Confirms Step 3 alone isn't enough.

- [ ] **Step 5: Create the response-shape helpers**

Create `Android/src/app/src/main/java/com/google/ai/edge/gallery/apiserver/ChatCompletionsMapping.kt`:

```kotlin
package com.google.ai.edge.gallery.apiserver

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

private val json = Json { encodeDefaults = true }

@Serializable
data class ChatCompletionChoice(val index: Int = 0, val message: ChatCompletionResponseMessage, val finishReason: String = "stop")

@Serializable data class ChatCompletionResponseMessage(val role: String = "assistant", val content: String)

@Serializable
data class ChatCompletionResponse(
  val id: String,
  val `object`: String = "chat.completion",
  val choices: List<ChatCompletionChoice>,
)

@Serializable data class ErrorBody(val message: String)

@Serializable data class ErrorResponse(val error: ErrorBody)

/** Builds the OpenAI-shaped success response JSON for a completed turn's [output] text. */
fun chatCompletionSuccessJson(output: String): String {
  val response =
    ChatCompletionResponse(
      id = "gallery-${UUID.randomUUID()}",
      choices = listOf(ChatCompletionChoice(message = ChatCompletionResponseMessage(content = output))),
    )
  return json.encodeToString(response)
}

/** Builds the OpenAI-shaped error response JSON for a failure [message]. */
fun errorResponseJson(message: String): String = json.encodeToString(ErrorResponse(ErrorBody(message)))
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `cd Android/src && ./gradlew :app:testDebugUnitTest --tests "com.google.ai.edge.gallery.apiserver.ChatCompletionsMappingTest"`
Expected: `BUILD SUCCESSFUL`, 7 tests passed.

- [ ] **Step 7: Commit**

```bash
git add Android/src/app/src/main/java/com/google/ai/edge/gallery/apiserver/ChatCompletionsModels.kt \
        Android/src/app/src/main/java/com/google/ai/edge/gallery/apiserver/ChatCompletionsMapping.kt \
        Android/src/app/src/test/java/com/google/ai/edge/gallery/apiserver/ChatCompletionsMappingTest.kt
git commit -m "feat(apiserver): add OpenAI-compatible chat completion request/response mapping"
```

---

### Task 4: Local API server settings (Preferences DataStore)

**Files:**
- Create: `Android/src/app/src/main/java/com/google/ai/edge/gallery/apiserver/LocalApiServerPreferences.kt`
- Modify: `Android/src/app/src/main/java/com/google/ai/edge/gallery/di/AppModule.kt`
- Test (new): `Android/src/app/src/test/java/com/google/ai/edge/gallery/apiserver/LocalApiServerPreferencesTest.kt`

- [ ] **Step 1: Write the failing test**

Create `Android/src/app/src/test/java/com/google/ai/edge/gallery/apiserver/LocalApiServerPreferencesTest.kt`:

```kotlin
package com.google.ai.edge.gallery.apiserver

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue

class LocalApiServerPreferencesTest {
  private lateinit var file: File
  private lateinit var prefs: LocalApiServerPreferences

  @Before
  fun setUp() {
    file = File.createTempFile("local_api_server_prefs_test", ".preferences_pb")
    val dataStore = PreferenceDataStoreFactory.create { file }
    prefs = LocalApiServerPreferences(dataStore)
  }

  @After
  fun tearDown() {
    file.delete()
  }

  @Test
  fun `enabled defaults to false`() = runTest {
    assertEquals(false, prefs.readEnabled())
  }

  @Test
  fun `enabled round-trips through save and read`() = runTest {
    prefs.saveEnabled(true)
    assertEquals(true, prefs.readEnabled())
  }

  @Test
  fun `port defaults to 8080`() = runTest {
    assertEquals(8080, prefs.readPort())
  }

  @Test
  fun `port round-trips through save and read`() = runTest {
    prefs.savePort(9090)
    assertEquals(9090, prefs.readPort())
  }

  @Test
  fun `token is generated on first read and stable afterwards`() = runTest {
    val first = prefs.readOrCreateToken()
    assertTrue(first.isNotBlank())
    val second = prefs.readOrCreateToken()
    assertEquals(first, second)
  }

  @Test
  fun `regenerateToken produces a different token`() = runTest {
    val first = prefs.readOrCreateToken()
    val second = prefs.regenerateToken()
    assertNotEquals(first, second)
    assertEquals(second, prefs.readOrCreateToken())
  }
}
```

- [ ] **Step 2: Run the test to verify it fails to compile**

Run: `cd Android/src && ./gradlew :app:testDebugUnitTest --tests "com.google.ai.edge.gallery.apiserver.LocalApiServerPreferencesTest"`
Expected: compile error — `LocalApiServerPreferences` doesn't exist yet.

- [ ] **Step 3: Implement `LocalApiServerPreferences`**

Create `Android/src/app/src/main/java/com/google/ai/edge/gallery/apiserver/LocalApiServerPreferences.kt`:

```kotlin
package com.google.ai.edge.gallery.apiserver

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

private val ENABLED_KEY = booleanPreferencesKey("local_api_server_enabled")
private val PORT_KEY = intPreferencesKey("local_api_server_port")
private val TOKEN_KEY = stringPreferencesKey("local_api_server_token")

const val DEFAULT_LOCAL_API_SERVER_PORT = 8080

/** Reads and writes the on/off toggle, port, and bearer token for the local API server. */
@Singleton
class LocalApiServerPreferences @Inject constructor(private val dataStore: DataStore<Preferences>) {
  suspend fun readEnabled(): Boolean = dataStore.data.first()[ENABLED_KEY] ?: false

  suspend fun saveEnabled(enabled: Boolean) {
    dataStore.edit { it[ENABLED_KEY] = enabled }
  }

  suspend fun readPort(): Int = dataStore.data.first()[PORT_KEY] ?: DEFAULT_LOCAL_API_SERVER_PORT

  suspend fun savePort(port: Int) {
    dataStore.edit { it[PORT_KEY] = port }
  }

  /** Returns the existing token, or generates and persists a new one if none exists yet. */
  suspend fun readOrCreateToken(): String {
    val existing = dataStore.data.first()[TOKEN_KEY]
    if (existing != null) return existing
    return regenerateToken()
  }

  /** Generates a new random token, persists it, and returns it. */
  suspend fun regenerateToken(): String {
    val token = UUID.randomUUID().toString()
    dataStore.edit { it[TOKEN_KEY] = token }
    return token
  }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd Android/src && ./gradlew :app:testDebugUnitTest --tests "com.google.ai.edge.gallery.apiserver.LocalApiServerPreferencesTest"`
Expected: `BUILD SUCCESSFUL`, 6 tests passed.

- [ ] **Step 5: Provide the Preferences DataStore via Hilt**

In `Android/src/app/src/main/java/com/google/ai/edge/gallery/di/AppModule.kt`, add these imports:

```kotlin
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
```

Then, after the existing `provideSkillsDataStore` provider function (currently ending around line 155), add:

```kotlin
  private val Context.localApiServerDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "local_api_server_prefs")

  @Provides
  @Singleton
  fun provideLocalApiServerDataStore(@ApplicationContext context: Context): DataStore<Preferences> {
    return context.localApiServerDataStore
  }
```

(`@ApplicationContext` and `DataStore` are already imported in this file for the other providers.)

- [ ] **Step 6: Verify the app module still compiles**

Run: `cd Android/src && ./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

```bash
git add Android/src/app/src/main/java/com/google/ai/edge/gallery/apiserver/LocalApiServerPreferences.kt \
        Android/src/app/src/main/java/com/google/ai/edge/gallery/di/AppModule.kt \
        Android/src/app/src/test/java/com/google/ai/edge/gallery/apiserver/LocalApiServerPreferencesTest.kt
git commit -m "feat(apiserver): add settings store for the local API server toggle, port, and token"
```

---

### Task 5: `LocalApiForegroundService` — the Ktor server itself

**Files:**
- Create: `Android/src/app/src/main/java/com/google/ai/edge/gallery/apiserver/LocalApiForegroundService.kt`
- Modify: `Android/src/app/src/main/AndroidManifest.xml`

This task wires everything from Tasks 2–4 into a running Android foreground service. It cannot be unit-tested (it needs a real Android device with a model loaded); it is verified manually in Task 7.

- [ ] **Step 1: Add the notification channel constant and service class**

Create `Android/src/app/src/main/java/com/google/ai/edge/gallery/apiserver/LocalApiForegroundService.kt`:

```kotlin
package com.google.ai.edge.gallery.apiserver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.IBinder
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.ai.edge.gallery.agent.AgentExecutionContext
import com.google.ai.edge.gallery.agent.AgentRequest
import com.google.ai.edge.gallery.agent.AgentRuntimeConfig
import com.google.ai.edge.gallery.agent.AgentRuntimeExecutor
import com.google.ai.edge.gallery.agent.AiChatExecutor
import com.google.ai.edge.gallery.agent.Attachment
import com.google.ai.edge.litertlm.Message
import dagger.hilt.android.AndroidEntryPoint
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import javax.inject.Inject

private const val TAG = "AGLocalApiServer"
private const val NOTIFICATION_CHANNEL_ID = "local_api_server"
private const val NOTIFICATION_ID = 4201

@AndroidEntryPoint
class LocalApiForegroundService : Service() {

  @Inject @AiChatExecutor lateinit var executor: AgentRuntimeExecutor
  @Inject lateinit var preferences: LocalApiServerPreferences

  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val requestMutex = Mutex()
  private var server: EmbeddedServer<*, *>? = null
  private val requestJson = Json { ignoreUnknownKeys = true }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val port = runBlocking { preferences.readPort() }
    val token = runBlocking { preferences.readOrCreateToken() }

    startForeground(
      NOTIFICATION_ID,
      buildNotification(port),
      ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )

    try {
      server =
        embeddedServer(CIO, port = port, host = "0.0.0.0") {
            install(ContentNegotiation) { json() }
            routing {
              get("/v1/models") { handleListModels(this) }
              post("/v1/chat/completions") { handleChatCompletions(this, token) }
            }
          }
          .start(wait = false)
    } catch (e: Exception) {
      Log.e(TAG, "Failed to start local API server on port $port", e)
      stopSelf()
    }

    return START_STICKY
  }

  override fun onDestroy() {
    server?.stop(gracePeriodMillis = 200, timeoutMillis = 1000)
    server = null
    super.onDestroy()
  }

  private suspend fun handleListModels(call: io.ktor.server.application.ApplicationCall) {
    val modelName = executor.activeModelInfo?.model?.name
    val ids = if (modelName != null) """[{"id":"$modelName"}]""" else "[]"
    call.respondText(contentType = io.ktor.http.ContentType.Application.Json, text = """{"data":$ids}""")
  }

  private suspend fun handleChatCompletions(
    call: io.ktor.server.application.ApplicationCall,
    expectedToken: String,
  ) {
    val authHeader = call.request.header("Authorization")
    if (authHeader != "Bearer $expectedToken") {
      call.respondText(
        contentType = io.ktor.http.ContentType.Application.Json,
        status = HttpStatusCode.Unauthorized,
        text = errorResponseJson("Invalid or missing API token"),
      )
      return
    }

    val info = executor.activeModelInfo
    if (info == null) {
      call.respondText(
        contentType = io.ktor.http.ContentType.Application.Json,
        status = HttpStatusCode.ServiceUnavailable,
        text = errorResponseJson("No model loaded in Gallery. Open the app and load a model first."),
      )
      return
    }

    val request =
      try {
        requestJson.decodeFromString(ChatCompletionRequest.serializer(), call.receiveText())
      } catch (e: Exception) {
        call.respondText(
          contentType = io.ktor.http.ContentType.Application.Json,
          status = HttpStatusCode.BadRequest,
          text = errorResponseJson("Malformed request: ${e.message}"),
        )
        return
      }

    if (request.messages.isEmpty()) {
      call.respondText(
        contentType = io.ktor.http.ContentType.Application.Json,
        status = HttpStatusCode.BadRequest,
        text = errorResponseJson("`messages` must not be empty"),
      )
      return
    }

    requestMutex.withLock {
      val history = request.messages.dropLast(1)
      val lastMessage = request.messages.last()

      val initialMessages =
        history.mapNotNull { msg ->
          when (msg.liteRtRole()) {
            LiteRtRole.USER -> Message.user(msg.textContent())
            LiteRtRole.MODEL -> Message.model(msg.textContent())
            null -> null
          }
        }

      executor.resetSession(
        AgentRuntimeConfig(
          model = info.model,
          taskId = info.taskId,
          supportImage = info.supportImage,
          initialMessages = initialMessages,
        )
      )

      val images = lastMessage.imageBase64Payloads().mapNotNull { decodeBase64ToBitmap(it) }
      val response =
        executor.execute(
          context = AgentExecutionContext(),
          request =
            AgentRequest(
              query = lastMessage.textContent(),
              attachments = images.map { Attachment.ImageBitmap(it) },
            ),
        )

      if (response.isSuccessful) {
        call.respondText(
          contentType = io.ktor.http.ContentType.Application.Json,
          text = chatCompletionSuccessJson(response.output),
        )
      } else {
        call.respondText(
          contentType = io.ktor.http.ContentType.Application.Json,
          status = HttpStatusCode.InternalServerError,
          text = errorResponseJson(response.output),
        )
      }
    }
  }

  private fun decodeBase64ToBitmap(base64: String): Bitmap? =
    try {
      val bytes = Base64.decode(base64, Base64.DEFAULT)
      BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    } catch (e: Exception) {
      Log.w(TAG, "Failed to decode an image_url payload", e)
      null
    }

  private fun buildNotification(port: Int): Notification {
    val manager = getSystemService(NotificationManager::class.java)
    if (manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) == null) {
      manager.createNotificationChannel(
        NotificationChannel(
          NOTIFICATION_CHANNEL_ID,
          "Local API server",
          NotificationManager.IMPORTANCE_LOW,
        )
      )
    }
    return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
      .setContentTitle("Local API server running")
      .setContentText("Serving http://0.0.0.0:$port/v1")
      .setSmallIcon(android.R.drawable.ic_menu_share)
      .setOngoing(true)
      .build()
  }
}
```

- [ ] **Step 2: Register the service in the manifest**

In `Android/src/app/src/main/AndroidManifest.xml`, right after the existing `SystemForegroundService` block (after line 124), add:

```xml
        <service
            android:name="com.google.ai.edge.gallery.apiserver.LocalApiForegroundService"
            android:foregroundServiceType="dataSync"
            android:exported="false" />
```

- [ ] **Step 3: Verify the app compiles and builds**

Run: `cd Android/src && ./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add Android/src/app/src/main/java/com/google/ai/edge/gallery/apiserver/LocalApiForegroundService.kt \
        Android/src/app/src/main/AndroidManifest.xml
git commit -m "feat(apiserver): add the local OpenAI-compatible API foreground service"
```

---

### Task 6: Settings UI toggle

**Files:**
- Modify: `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/modelmanager/ModelManagerViewModel.kt`
- Modify: `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/home/SettingsDialog.kt`

- [ ] **Step 1: Add settings accessors to `ModelManagerViewModel`**

In `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/modelmanager/ModelManagerViewModel.kt`, add this import:

```kotlin
import com.google.ai.edge.gallery.apiserver.LocalApiServerPreferences
import com.google.ai.edge.gallery.apiserver.LocalApiForegroundService
import android.content.Intent
```

Add `localApiServerPreferences: LocalApiServerPreferences` as a new constructor parameter (after the existing `@ApplicationContext private val context: Context` parameter on line 212).

Then, right after the existing `saveFirebaseAnalytics`-related code (around line 935-945), add:

```kotlin
  fun readLocalApiServerEnabled(onResult: (Boolean) -> Unit) {
    viewModelScope.launch { onResult(localApiServerPreferences.readEnabled()) }
  }

  fun readLocalApiServerPort(onResult: (Int) -> Unit) {
    viewModelScope.launch { onResult(localApiServerPreferences.readPort()) }
  }

  fun readLocalApiServerToken(onResult: (String) -> Unit) {
    viewModelScope.launch { onResult(localApiServerPreferences.readOrCreateToken()) }
  }

  fun regenerateLocalApiServerToken(onResult: (String) -> Unit) {
    viewModelScope.launch { onResult(localApiServerPreferences.regenerateToken()) }
  }

  fun setLocalApiServerEnabled(enabled: Boolean) {
    viewModelScope.launch {
      localApiServerPreferences.saveEnabled(enabled)
      val intent = Intent(context, LocalApiForegroundService::class.java)
      if (enabled) {
        context.startForegroundService(intent)
      } else {
        context.stopService(intent)
      }
    }
  }
```

- [ ] **Step 2: Add the Settings UI section**

In `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/home/SettingsDialog.kt`, add this import:

```kotlin
import androidx.compose.runtime.LaunchedEffect
```

Then, right after the closing of the Firebase Analytics `Row` block (after line 230, before the "HF Token management" comment), add:

```kotlin
          // Local API server toggle.
          var localApiServerEnabled by remember { mutableStateOf(false) }
          var localApiServerPort by remember { mutableStateOf(8080) }
          var localApiServerToken by remember { mutableStateOf("") }
          LaunchedEffect(Unit) {
            modelManagerViewModel.readLocalApiServerEnabled { localApiServerEnabled = it }
            modelManagerViewModel.readLocalApiServerPort { localApiServerPort = it }
            modelManagerViewModel.readLocalApiServerToken { localApiServerToken = it }
          }
          Column(
            modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
            verticalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween,
            ) {
              Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                Text(
                  "Expose local API server",
                  style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
                )
                Text(
                  "Lets other devices on this WiFi network call the loaded model as an" +
                    " OpenAI-compatible /v1/chat/completions endpoint.",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
              Switch(
                checked = localApiServerEnabled,
                onCheckedChange = { checked ->
                  localApiServerEnabled = checked
                  modelManagerViewModel.setLocalApiServerEnabled(checked)
                },
              )
            }
            if (localApiServerEnabled) {
              Text(
                "Port: $localApiServerPort",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              Text(
                "Token: $localApiServerToken",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
```

- [ ] **Step 3: Verify the app compiles and builds**

Run: `cd Android/src && ./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/modelmanager/ModelManagerViewModel.kt \
        Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/home/SettingsDialog.kt
git commit -m "feat(apiserver): add Settings toggle for the local API server"
```

---

### Task 7: Manual on-device verification

**Files:** none (verification only, per the spec's Testing section)

- [ ] **Step 1: Install and load a model**

Build and install the debug APK on a connected device (`./gradlew :app:installDebug`), open Gallery, and load any chat-capable model (e.g. Gemma 3n E2B) via the "AI Chat" task until it's ready to answer.

- [ ] **Step 2: Enable the API server**

Open Settings, toggle "Expose local API server" on. Confirm a persistent notification appears ("Local API server running") and the Settings section shows a port and token.

- [ ] **Step 3: Find the phone's LAN IP**

On the phone: Settings → About phone → Status → IP address (or run `adb shell ip route` from the PC while the phone is connected via USB, and read the `src` address).

- [ ] **Step 4: Send a text-only request from the PC**

```bash
curl -s http://<phone-ip>:8080/v1/chat/completions \
  -H "Authorization: Bearer <token from Settings>" \
  -H "Content-Type: application/json" \
  -d '{"messages":[{"role":"user","content":"Say hello in one short sentence."}]}'
```

Expected: HTTP 200, a JSON body with `choices[0].message.content` containing a short greeting.

- [ ] **Step 5: Confirm the auth check works**

Repeat Step 4 with no `Authorization` header, or a wrong token.
Expected: HTTP 401 with `{"error":{"message":"Invalid or missing API token"}}`.

- [ ] **Step 6: Send a request with an embedded image**

```bash
IMG_B64=$(base64 -w0 some_test_image.png)
curl -s http://<phone-ip>:8080/v1/chat/completions \
  -H "Authorization: Bearer <token from Settings>" \
  -H "Content-Type: application/json" \
  -d "{\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"What is in this image?\"},{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/png;base64,$IMG_B64\"}}]}]}"
```

Expected: HTTP 200, a response that plausibly describes the image contents.

- [ ] **Step 7: Confirm the "no model loaded" case**

In Gallery's UI, unload/leave the chat screen so no model is initialized (or test this before Step 1's model load). Repeat Step 4.
Expected: HTTP 503 with `{"error":{"message":"No model loaded in Gallery. Open the app and load a model first."}}`.

- [ ] **Step 8: ARTEMIS integration smoke test**

In `C:\Users\Anand\artemis\config\artemis.jsonc`, add a preset:

```jsonc
"gallery-local": {
  "provider": "ollama",
  "model": "gemma-local",
  "fallback": { "provider": "ollama", "model": "gemma-local" }
}
```

Set `"default"` (or a specific node) to use this preset, and in `C:\Users\Anand\artemis\.env` set:

```
OPENAI_BASE_URL=http://<phone-ip>:8080/v1
OPENAI_API_KEY=<token from Settings>
```

Restart the ARTEMIS MCP server (reload MCP servers), then run one simple `mobile_run_task` (e.g. "open Settings"). Confirm ARTEMIS receives and acts on a response from the phone's on-device model instead of erroring on a missing Gemini/OpenAI key.

- [ ] **Step 9: Record the outcome**

If all steps pass, update the spec's Status line from "Approved for implementation" to "Implemented, verified on-device <date>". If something didn't work, note it in the spec's "Open risks" section instead of silently reworking the code.

```bash
git add docs/superpowers/specs/2026-09-17-local-api-server-design.md
git commit -m "docs: record manual verification of the local API server"
```

---

## Self-review notes

- **Spec coverage:** Goals (OpenAI shape, on-device, Settings-toggled) → Tasks 5–6. Non-goals respected: no multi-model loading, no streaming, no audio over the API. Conversation-state reset+replay → Task 5 Step 1 (`resetSession` + `execute`). Error table → Task 5 Step 1 (401/503/400/500 branches). Testing section → Tasks 2–4 unit tests + Task 7 manual script.
- **Type consistency:** `ActiveModelInfo(model, taskId, supportImage)` (Task 2) is read the same way in Task 5's `handleChatCompletions`. `ChatCompletionMessage.textContent()` / `.imageBase64Payloads()` / `.liteRtRole()` (Task 3) are the only mapping surface Task 5 calls — no duplicate ad hoc parsing was introduced there.
- **Known gap intentionally deferred:** `LocalApiForegroundService` itself has no automated test (needs a real device + loaded model); Task 7's manual script is the acceptance test for it, matching the spec's Testing section.
