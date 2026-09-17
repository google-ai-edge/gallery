# API Server Mode Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the bug where closing the AI Chat screen unloads the model the local API server needs, and let the user pick which downloaded model the server serves.

**Architecture:** A tiny in-memory `ApiServerSessionHold` singleton guards `DefaultAgentRuntimeExecutor.cleanUp()` — if the API server currently holds the active session's model, cleanup is skipped instead of tearing the model down. `LocalApiForegroundService` now initializes its chosen model itself (via a new `ModelCatalogCache` that mirrors `ModelManagerViewModel`'s downloaded-model list into a plain singleton the Service can read) instead of depending on a chat screen having loaded one first.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt, kotlinx.coroutines, JUnit4 (JVM unit tests). No new external dependencies.

**Spec:** `docs/superpowers/specs/2026-09-17-api-server-mode-design.md`

**Repo root for all paths below:** `Android/src/app/`

---

### Task 1: `ApiServerSessionHold`

**Files:**
- Create: `Android/src/app/src/main/java/com/google/ai/edge/gallery/apiserver/ApiServerSessionHold.kt`
- Test (new): `Android/src/app/src/test/java/com/google/ai/edge/gallery/apiserver/ApiServerSessionHoldTest.kt`

- [x] **Step 1: Write the failing test**

Create `Android/src/app/src/test/java/com/google/ai/edge/gallery/apiserver/ApiServerSessionHoldTest.kt`:

```kotlin
package com.google.ai.edge.gallery.apiserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApiServerSessionHoldTest {
  @Test
  fun `heldModelName defaults to null`() {
    assertNull(ApiServerSessionHold().heldModelName)
  }

  @Test
  fun `heldModelName can be set and read back`() {
    val hold = ApiServerSessionHold()
    hold.heldModelName = "Gemma-4-E2B-it"
    assertEquals("Gemma-4-E2B-it", hold.heldModelName)
  }

  @Test
  fun `heldModelName can be cleared`() {
    val hold = ApiServerSessionHold()
    hold.heldModelName = "Gemma-4-E2B-it"
    hold.heldModelName = null
    assertNull(hold.heldModelName)
  }
}
```

- [x] **Step 2: Run the test to verify it fails to compile**

Run: `cd Android/src && ./gradlew :app:testDebugUnitTest --tests "com.google.ai.edge.gallery.apiserver.ApiServerSessionHoldTest"`
Expected: compile error — `ApiServerSessionHold` doesn't exist yet.

- [x] **Step 3: Implement `ApiServerSessionHold`**

Create `Android/src/app/src/main/java/com/google/ai/edge/gallery/apiserver/ApiServerSessionHold.kt`:

```kotlin
package com.google.ai.edge.gallery.apiserver

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks which model name, if any, the local API server currently needs kept alive.
 *
 * [DefaultAgentRuntimeExecutor.cleanUp] checks this before tearing down the active session: if
 * the session's model matches [heldModelName], cleanup is skipped so a screen closing (AI Chat,
 * Ask Image, etc.) doesn't unload a model the API server still needs. Plain in-memory state, not a
 * DataStore round-trip: `cleanUp()` is not suspend-safe and must answer synchronously.
 */
@Singleton
class ApiServerSessionHold @Inject constructor() {
  @Volatile var heldModelName: String? = null
}
```

- [x] **Step 4: Run the test to verify it passes**

Run: `cd Android/src && ./gradlew :app:testDebugUnitTest --tests "com.google.ai.edge.gallery.apiserver.ApiServerSessionHoldTest"`
Expected: `BUILD SUCCESSFUL`, 3 tests passed.

- [x] **Step 5: Commit**

```bash
git add Android/src/app/src/main/java/com/google/ai/edge/gallery/apiserver/ApiServerSessionHold.kt \
        Android/src/app/src/test/java/com/google/ai/edge/gallery/apiserver/ApiServerSessionHoldTest.kt
git commit -m "feat(apiserver): add ApiServerSessionHold to guard shared-session cleanup"
```

---

### Task 2: Guard `DefaultAgentRuntimeExecutor.cleanUp()`

**Files:**
- Modify: `Android/src/app/src/main/java/com/google/ai/edge/gallery/agent/DefaultAgentRuntimeExecutor.kt`
- Modify: `Android/src/app/src/main/java/com/google/ai/edge/gallery/agent/AgentExecutorModule.kt`
- Modify: `Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/agentchat/AgentChatTaskModule.kt`
- Test (new): `Android/src/app/src/test/java/com/google/ai/edge/gallery/agent/DefaultAgentRuntimeExecutorCleanUpHoldTest.kt`

`DefaultAgentRuntimeExecutor` is constructed in two places (`@AiChatExecutor` for AI Chat/Ask Image/Prompt Lab/etc., and `@AgentChatExecutor` for Agent Skills) — both need the new constructor parameter since it's the same class, even though only `@AiChatExecutor` is ever actually held by the API server.

- [x] **Step 1: Write the failing test**

Create `Android/src/app/src/test/java/com/google/ai/edge/gallery/agent/DefaultAgentRuntimeExecutorCleanUpHoldTest.kt`:

```kotlin
package com.google.ai.edge.gallery.agent

import android.graphics.Bitmap
import com.google.ai.edge.gallery.agent.sessions.LlmSessionManager
import com.google.ai.edge.gallery.agent.sessions.SessionConfig
import com.google.ai.edge.gallery.agent.sessions.generateSessionId
import com.google.ai.edge.gallery.apiserver.ApiServerSessionHold
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

class DefaultAgentRuntimeExecutorCleanUpHoldTest {
  private fun newExecutor(hold: ApiServerSessionHold) =
    DefaultAgentRuntimeExecutor(
      skillsProvider = NoOpSkillsProvider(),
      toolsProvider = RuntimeToolsProvider(),
      toolDispatcher = RuntimeToolDispatcher(),
      llmSessionManager = FakeLlmSessionManager(),
      apiServerSessionHold = hold,
    )

  @Test
  fun `cleanUp is a no-op when the hold matches the active model`() = runBlocking {
    val hold = ApiServerSessionHold().apply { heldModelName = "test-model" }
    val executor = newExecutor(hold)
    executor.resetSession(AgentRuntimeConfig(model = Model(name = "test-model"), taskId = "llm_chat"))

    var onDoneCalled = false
    executor.cleanUp { onDoneCalled = true }

    assertEquals("test-model", executor.activeModelInfo?.model?.name)
    assertEquals(true, onDoneCalled)
  }

  @Test
  fun `cleanUp tears down when the hold does not match`() = runBlocking {
    val hold = ApiServerSessionHold().apply { heldModelName = "other-model" }
    val executor = newExecutor(hold)
    executor.resetSession(AgentRuntimeConfig(model = Model(name = "test-model"), taskId = "llm_chat"))

    executor.cleanUp {}

    assertNull(executor.activeModelInfo)
  }

  @Test
  fun `cleanUp tears down when there is no hold`() = runBlocking {
    val executor = newExecutor(ApiServerSessionHold())
    executor.resetSession(AgentRuntimeConfig(model = Model(name = "test-model"), taskId = "llm_chat"))

    executor.cleanUp {}

    assertNull(executor.activeModelInfo)
  }
}
```

- [x] **Step 2: Run the test to verify it fails to compile**

Run: `cd Android/src && ./gradlew :app:testDebugUnitTest --tests "com.google.ai.edge.gallery.agent.DefaultAgentRuntimeExecutorCleanUpHoldTest"`
Expected: compile error — `DefaultAgentRuntimeExecutor` has no `apiServerSessionHold` parameter yet.

- [x] **Step 3: Add the constructor parameter and guard**

In `Android/src/app/src/main/java/com/google/ai/edge/gallery/agent/DefaultAgentRuntimeExecutor.kt`, add this import:

```kotlin
import com.google.ai.edge.gallery.apiserver.ApiServerSessionHold
```

Change the constructor (currently):

```kotlin
open class DefaultAgentRuntimeExecutor(
  val skillsProvider: SkillsProvider,
  val toolsProvider: ToolsProvider,
  val toolDispatcher: ToolDispatcher,
  val llmSessionManager: LlmSessionManager,
) : AgentRuntimeExecutor {
```

to:

```kotlin
open class DefaultAgentRuntimeExecutor(
  val skillsProvider: SkillsProvider,
  val toolsProvider: ToolsProvider,
  val toolDispatcher: ToolDispatcher,
  val llmSessionManager: LlmSessionManager,
  val apiServerSessionHold: ApiServerSessionHold,
) : AgentRuntimeExecutor {
```

Then replace the existing `cleanUp` override:

```kotlin
  override fun cleanUp(onDone: () -> Unit) {
    val session = activeSession.getAndSet(null)
    if (session == null) {
      onDone()
      return
    }
    session.sessionConfig.model.runtimeHelper.cleanUp(
      model = session.sessionConfig.model,
      onDone = onDone,
    )
  }
```

with:

```kotlin
  override fun cleanUp(onDone: () -> Unit) {
    val session = activeSession.get()
    if (session != null && apiServerSessionHold.heldModelName == session.sessionConfig.model.name) {
      // The local API server still needs this model kept alive; skip teardown.
      onDone()
      return
    }
    val cleared = activeSession.getAndSet(null)
    if (cleared == null) {
      onDone()
      return
    }
    cleared.sessionConfig.model.runtimeHelper.cleanUp(
      model = cleared.sessionConfig.model,
      onDone = onDone,
    )
  }
```

- [x] **Step 4: Update both provider modules**

In `Android/src/app/src/main/java/com/google/ai/edge/gallery/agent/AgentExecutorModule.kt`, add this import:

```kotlin
import com.google.ai.edge.gallery.apiserver.ApiServerSessionHold
```

Change:

```kotlin
  fun provideAiChatExecutor(llmSessionManager: LlmSessionManager): AgentRuntimeExecutor {
    return DefaultAgentRuntimeExecutor(
      skillsProvider = NoOpSkillsProvider(),
      toolsProvider = RuntimeToolsProvider(),
      toolDispatcher = RuntimeToolDispatcher(),
      llmSessionManager = llmSessionManager,
    )
  }
```

to:

```kotlin
  fun provideAiChatExecutor(
    llmSessionManager: LlmSessionManager,
    apiServerSessionHold: ApiServerSessionHold,
  ): AgentRuntimeExecutor {
    return DefaultAgentRuntimeExecutor(
      skillsProvider = NoOpSkillsProvider(),
      toolsProvider = RuntimeToolsProvider(),
      toolDispatcher = RuntimeToolDispatcher(),
      llmSessionManager = llmSessionManager,
      apiServerSessionHold = apiServerSessionHold,
    )
  }
```

In `Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/agentchat/AgentChatTaskModule.kt`, add the same import and change:

```kotlin
  fun provideAgentChatExecutor(
    skillManager: SkillManager,
    agentTools: AgentTools,
    llmSessionManager: LlmSessionManager,
  ): AgentRuntimeExecutor {
    return DefaultAgentRuntimeExecutor(
      skillsProvider = skillManager,
      toolsProvider = agentTools,
      toolDispatcher = RuntimeToolDispatcher(),
      llmSessionManager = llmSessionManager,
    )
  }
```

to:

```kotlin
  fun provideAgentChatExecutor(
    skillManager: SkillManager,
    agentTools: AgentTools,
    llmSessionManager: LlmSessionManager,
    apiServerSessionHold: ApiServerSessionHold,
  ): AgentRuntimeExecutor {
    return DefaultAgentRuntimeExecutor(
      skillsProvider = skillManager,
      toolsProvider = agentTools,
      toolDispatcher = RuntimeToolDispatcher(),
      llmSessionManager = llmSessionManager,
      apiServerSessionHold = apiServerSessionHold,
    )
  }
```

(`ApiServerSessionHold` has an `@Inject constructor()`, so Hilt supplies it to both `@Provides` functions automatically — no new `@Provides` needed for it.)

- [x] **Step 5: Run the test to verify it passes**

Run: `cd Android/src && ./gradlew :app:testDebugUnitTest --tests "com.google.ai.edge.gallery.agent.DefaultAgentRuntimeExecutorCleanUpHoldTest"`
Expected: `BUILD SUCCESSFUL`, 3 tests passed.

- [x] **Step 6: Run the full existing agent test suite to check for regressions**

Run: `cd Android/src && ./gradlew :app:testDebugUnitTest --tests "com.google.ai.edge.gallery.agent.*"`
Expected: `BUILD SUCCESSFUL`, all tests (including `DefaultAgentRuntimeExecutorActiveModelInfoTest` from the previous plan) still pass.

- [x] **Step 7: Verify the app still builds**

Run: `cd Android/src && ./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`.

- [x] **Step 8: Commit**

```bash
git add Android/src/app/src/main/java/com/google/ai/edge/gallery/agent/DefaultAgentRuntimeExecutor.kt \
        Android/src/app/src/main/java/com/google/ai/edge/gallery/agent/AgentExecutorModule.kt \
        Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/agentchat/AgentChatTaskModule.kt \
        Android/src/app/src/test/java/com/google/ai/edge/gallery/agent/DefaultAgentRuntimeExecutorCleanUpHoldTest.kt
git commit -m "fix(agent): don't unload the model the API server holds when a screen exits"
```

---

### Task 3: `ModelCatalogCache`

**Files:**
- Create: `Android/src/app/src/main/java/com/google/ai/edge/gallery/apiserver/ModelCatalogCache.kt`
- Modify: `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/modelmanager/ModelManagerViewModel.kt`
- Test (new): `Android/src/app/src/test/java/com/google/ai/edge/gallery/apiserver/ModelCatalogCacheTest.kt`

- [x] **Step 1: Write the failing test**

Create `Android/src/app/src/test/java/com/google/ai/edge/gallery/apiserver/ModelCatalogCacheTest.kt`:

```kotlin
package com.google.ai.edge.gallery.apiserver

import com.google.ai.edge.gallery.data.Model
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogCacheTest {
  @Test
  fun `models starts empty`() {
    assertTrue(ModelCatalogCache().models.value.isEmpty())
  }

  @Test
  fun `update replaces the model list`() {
    val cache = ModelCatalogCache()
    val models = listOf(Model(name = "model-a"), Model(name = "model-b"))

    cache.update(models)

    assertEquals(models, cache.models.value)
  }

  @Test
  fun `a later update replaces, not appends`() {
    val cache = ModelCatalogCache()
    cache.update(listOf(Model(name = "model-a")))

    cache.update(listOf(Model(name = "model-b")))

    assertEquals(listOf(Model(name = "model-b")), cache.models.value)
  }
}
```

- [x] **Step 2: Run the test to verify it fails to compile**

Run: `cd Android/src && ./gradlew :app:testDebugUnitTest --tests "com.google.ai.edge.gallery.apiserver.ModelCatalogCacheTest"`
Expected: compile error — `ModelCatalogCache` doesn't exist yet.

- [x] **Step 3: Implement `ModelCatalogCache`**

Create `Android/src/app/src/main/java/com/google/ai/edge/gallery/apiserver/ModelCatalogCache.kt`:

```kotlin
package com.google.ai.edge.gallery.apiserver

import com.google.ai.edge.gallery.data.Model
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Mirrors [ModelManagerViewModel]'s downloaded-model list into a plain singleton.
 *
 * [LocalApiForegroundService] needs to resolve "which downloaded models exist" to pick one to
 * serve, but it can't inject [ModelManagerViewModel] directly -- that's a `@HiltViewModel`, which
 * requires a `ViewModelStoreOwner` a `Service` doesn't have. `ModelManagerViewModel` pushes its
 * already-computed downloaded-model list here instead of this cache owning any loading logic
 * itself.
 */
@Singleton
class ModelCatalogCache @Inject constructor() {
  private val _models = MutableStateFlow<List<Model>>(emptyList())
  val models: StateFlow<List<Model>> = _models.asStateFlow()

  fun update(models: List<Model>) {
    _models.value = models
  }
}
```

- [x] **Step 4: Run the test to verify it passes**

Run: `cd Android/src && ./gradlew :app:testDebugUnitTest --tests "com.google.ai.edge.gallery.apiserver.ModelCatalogCacheTest"`
Expected: `BUILD SUCCESSFUL`, 3 tests passed.

- [x] **Step 5: Wire `ModelManagerViewModel` to push into the cache**

In `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/modelmanager/ModelManagerViewModel.kt`, add this import:

```kotlin
import com.google.ai.edge.gallery.apiserver.ModelCatalogCache
```

Add `modelCatalogCache: ModelCatalogCache` as a new constructor parameter, right after `localApiServerPreferences` (currently at line 215):

```kotlin
  private val localApiServerPreferences: LocalApiServerPreferences,
  private val modelCatalogCache: ModelCatalogCache,
  @ApplicationContext private val context: Context,
```

Then, right after the `uiState` declaration (currently `open val uiState = _uiState.asStateFlow()`), add an `init` block that keeps the cache in sync with every state change:

```kotlin
  init {
    viewModelScope.launch { uiState.collect { modelCatalogCache.update(getAllDownloadedModels()) } }
  }
```

- [x] **Step 6: Verify the app compiles**

Run: `cd Android/src && ./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`.

- [x] **Step 7: Commit**

```bash
git add Android/src/app/src/main/java/com/google/ai/edge/gallery/apiserver/ModelCatalogCache.kt \
        Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/modelmanager/ModelManagerViewModel.kt \
        Android/src/app/src/test/java/com/google/ai/edge/gallery/apiserver/ModelCatalogCacheTest.kt
git commit -m "feat(apiserver): mirror the downloaded-model list into a Service-reachable cache"
```

---

### Task 4: Model picker preference

**Files:**
- Modify: `Android/src/app/src/main/java/com/google/ai/edge/gallery/apiserver/LocalApiServerPreferences.kt`
- Modify: `Android/src/app/src/test/java/com/google/ai/edge/gallery/apiserver/LocalApiServerPreferencesTest.kt`

- [x] **Step 1: Write the failing test**

In `Android/src/app/src/test/java/com/google/ai/edge/gallery/apiserver/LocalApiServerPreferencesTest.kt`, add these test methods inside the existing `LocalApiServerPreferencesTest` class (after `regenerateToken produces a different token`):

```kotlin
  @Test
  fun `selected model name defaults to null`() = runBlocking {
    assertEquals(null, prefs.readSelectedModelName())
  }

  @Test
  fun `selected model name round-trips through save and read`() = runBlocking {
    prefs.saveSelectedModelName("Gemma-4-E2B-it")
    assertEquals("Gemma-4-E2B-it", prefs.readSelectedModelName())
  }
```

- [x] **Step 2: Run the tests to verify they fail to compile**

Run: `cd Android/src && ./gradlew :app:testDebugUnitTest --tests "com.google.ai.edge.gallery.apiserver.LocalApiServerPreferencesTest"`
Expected: compile error — `readSelectedModelName`/`saveSelectedModelName` don't exist yet.

- [x] **Step 3: Add the preference field**

In `Android/src/app/src/main/java/com/google/ai/edge/gallery/apiserver/LocalApiServerPreferences.kt`, add this key alongside the existing ones:

```kotlin
private val SELECTED_MODEL_NAME_KEY = stringPreferencesKey("local_api_server_selected_model_name")
```

Add these two methods inside the `LocalApiServerPreferences` class, after `regenerateToken`:

```kotlin
  suspend fun readSelectedModelName(): String? = dataStore.data.first()[SELECTED_MODEL_NAME_KEY]

  suspend fun saveSelectedModelName(modelName: String) {
    dataStore.edit { it[SELECTED_MODEL_NAME_KEY] = modelName }
  }
```

- [x] **Step 4: Run the tests to verify they pass**

Run: `cd Android/src && ./gradlew :app:testDebugUnitTest --tests "com.google.ai.edge.gallery.apiserver.LocalApiServerPreferencesTest"`
Expected: `BUILD SUCCESSFUL` — 8 tests total, 7 passed, 1 skipped (the pre-existing `@Ignore`d `regenerateToken` test from the previous plan; the 2 new tests both pass).

- [x] **Step 5: Commit**

```bash
git add Android/src/app/src/main/java/com/google/ai/edge/gallery/apiserver/LocalApiServerPreferences.kt \
        Android/src/app/src/test/java/com/google/ai/edge/gallery/apiserver/LocalApiServerPreferencesTest.kt
git commit -m "feat(apiserver): add selected-model preference for the API server's model picker"
```

---

### Task 5: `LocalApiForegroundService` loads its own model

**Files:**
- Modify: `Android/src/app/src/main/java/com/google/ai/edge/gallery/apiserver/LocalApiForegroundService.kt`

Manual/integration only (needs a real device + downloaded models); verified in Task 7.

- [x] **Step 1: Add the new dependencies and a service-scoped coroutine scope**

Add these imports:

```kotlin
import com.google.ai.edge.gallery.data.BuiltInTaskId
```

Add these fields to the class, alongside the existing `@Inject` fields:

```kotlin
  @Inject lateinit var apiServerSessionHold: ApiServerSessionHold
  @Inject lateinit var modelCatalogCache: ModelCatalogCache

  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
```

- [x] **Step 2: Resolve and initialize the chosen model in `onStartCommand`**

Replace the current `onStartCommand`:

```kotlin
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
              get("/v1/models") { handleListModels(call) }
              post("/v1/chat/completions") { handleChatCompletions(call, token) }
            }
          }
          .start(wait = false)
    } catch (e: Exception) {
      Log.e(TAG, "Failed to start local API server on port $port", e)
      stopSelf()
    }

    return START_STICKY
  }
```

with:

```kotlin
  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val port = runBlocking { preferences.readPort() }
    val token = runBlocking { preferences.readOrCreateToken() }
    val selectedModelName = runBlocking { preferences.readSelectedModelName() }

    val downloadedModels = modelCatalogCache.models.value
    val modelToServe =
      downloadedModels.firstOrNull { it.name == selectedModelName } ?: downloadedModels.firstOrNull()

    if (modelToServe == null) {
      Log.e(TAG, "No downloaded model available to serve")
      showNoModelNotification()
      stopSelf()
      return START_NOT_STICKY
    }

    startForeground(
      NOTIFICATION_ID,
      buildNotification(port),
      ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )

    serviceScope.launch {
      executor.initialize(
        context = applicationContext,
        config =
          AgentRuntimeConfig(
            model = modelToServe,
            taskId = BuiltInTaskId.LLM_CHAT,
            supportImage = true,
          ),
        onDone = { errorMsg ->
          if (errorMsg.isEmpty()) {
            apiServerSessionHold.heldModelName = modelToServe.name
          } else {
            Log.e(TAG, "Failed to initialize ${modelToServe.name}: $errorMsg")
          }
        },
      )
    }

    try {
      server =
        embeddedServer(CIO, port = port, host = "0.0.0.0") {
            install(ContentNegotiation) { json() }
            routing {
              get("/v1/models") { handleListModels(call) }
              post("/v1/chat/completions") { handleChatCompletions(call, token) }
            }
          }
          .start(wait = false)
    } catch (e: Exception) {
      Log.e(TAG, "Failed to start local API server on port $port", e)
      stopSelf()
    }

    return START_STICKY
  }
```

(The server starts immediately rather than waiting for model init to finish; `/v1/models` and `/v1/chat/completions` already handle `activeModelInfo == null` with an empty list / 503 respectively, which is the correct behavior during the brief load window.)

- [x] **Step 3: Release the hold and clean up properly in `onDestroy`**

Replace:

```kotlin
  override fun onDestroy() {
    server?.stop(gracePeriodMillis = 200, timeoutMillis = 1000)
    server = null
    super.onDestroy()
  }
```

with:

```kotlin
  override fun onDestroy() {
    apiServerSessionHold.heldModelName = null
    executor.cleanUp()
    server?.stop(gracePeriodMillis = 200, timeoutMillis = 1000)
    server = null
    serviceScope.cancel()
    super.onDestroy()
  }
```

(Clearing the hold *before* calling `cleanUp()` matters: otherwise the guard added in Task 2 would see its own hold still set and skip the real teardown.)

- [x] **Step 4: Add the "no model" notification helper**

Add this method near `buildNotification`:

```kotlin
  private fun showNoModelNotification() {
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
    val notification =
      NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
        .setContentTitle("Local API server: no model to serve")
        .setContentText("Download one in AI Chat first")
        .setSmallIcon(android.R.drawable.ic_menu_share)
        .build()
    manager.notify(NOTIFICATION_ID, notification)
  }
```

- [x] **Step 5: Add the remaining imports**

```kotlin
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
```

- [x] **Step 6: Verify the app builds**

Run: `cd Android/src && ./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [x] **Step 7: Commit**

```bash
git add Android/src/app/src/main/java/com/google/ai/edge/gallery/apiserver/LocalApiForegroundService.kt
git commit -m "feat(apiserver): load the selected model on server start, independent of any chat screen"
```

---

### Task 6: Settings model picker UI

**Files:**
- Modify: `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/modelmanager/ModelManagerViewModel.kt`
- Modify: `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/home/SettingsDialog.kt`

- [x] **Step 1: Add picker read/write methods to `ModelManagerViewModel`**

Add these methods after the existing `setLocalApiServerEnabled` (added in the previous plan):

```kotlin
  fun readLocalApiServerSelectedModel(onResult: (String?) -> Unit) {
    viewModelScope.launch { onResult(localApiServerPreferences.readSelectedModelName()) }
  }

  fun setLocalApiServerSelectedModel(modelName: String) {
    viewModelScope.launch {
      localApiServerPreferences.saveSelectedModelName(modelName)
      if (localApiServerPreferences.readEnabled()) {
        context.startForegroundService(Intent(context, LocalApiForegroundService::class.java))
      }
    }
  }
```

- [x] **Step 2: Add the dropdown to `SettingsDialog.kt`**

Add these imports:

```kotlin
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.layout.Box
```

(`Box`, `Column`, `Row`, `Text`, `Switch`, `MaterialTheme`, `Modifier`, `mutableStateOf`, `remember`, `getValue`, `setValue` are already imported in this file.)

Replace the existing local-API-server block's port/token display:

```kotlin
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
```

with:

```kotlin
            if (localApiServerEnabled) {
              val downloadedModels = modelManagerViewModel.getAllDownloadedModels()
              var selectedModelName by remember { mutableStateOf<String?>(null) }
              var modelPickerExpanded by remember { mutableStateOf(false) }
              LaunchedEffect(Unit) {
                modelManagerViewModel.readLocalApiServerSelectedModel { selectedModelName = it }
              }
              Column {
                Text(
                  "Model to serve",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box {
                  Text(
                    selectedModelName ?: downloadedModels.firstOrNull()?.name ?: "No downloaded models",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier =
                      Modifier.clickable(enabled = downloadedModels.isNotEmpty()) {
                        modelPickerExpanded = true
                      },
                  )
                  DropdownMenu(
                    expanded = modelPickerExpanded,
                    onDismissRequest = { modelPickerExpanded = false },
                  ) {
                    downloadedModels.forEach { model ->
                      DropdownMenuItem(
                        text = { Text(model.name) },
                        onClick = {
                          selectedModelName = model.name
                          modelPickerExpanded = false
                          modelManagerViewModel.setLocalApiServerSelectedModel(model.name)
                        },
                      )
                    }
                  }
                }
              }
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
```

- [x] **Step 3: Verify the app builds**

Run: `cd Android/src && ./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [x] **Step 4: Commit**

```bash
git add Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/modelmanager/ModelManagerViewModel.kt \
        Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/home/SettingsDialog.kt
git commit -m "feat(apiserver): add a model picker to the Settings API server section"
```

---

### Task 7: Manual on-device verification

**Files:** none (verification only, per the spec's Testing section)

- [ ] **Step 1: Pick a model without opening AI Chat**

Install the updated APK. Without opening AI Chat at all this session, open Settings, enable "Expose local API server", and pick a downloaded model (e.g. `Gemma-4-E2B-it`) in the new dropdown.

- [ ] **Step 2: Confirm the server served that model immediately**

```bash
adb shell curl -s http://127.0.0.1:8080/v1/models
```
Expected: `{"data":[{"id":"Gemma-4-E2B-it"}]}` within a few seconds (model load time) — without ever having opened AI Chat.

- [ ] **Step 3: Confirm a text request works**

Repeat the text-completion curl from the previous plan's Task 7 Step 4. Expected: 200, valid response.

- [ ] **Step 4: Open AI Chat with a different downloaded model**

If a second model is downloaded, open AI Chat and select it. Repeat Step 2's curl.
Expected: `/v1/models` now reports the *chat-selected* model (documented takeover — matches the spec's non-goal).

- [ ] **Step 5: Confirm the actual bug fix — close AI Chat, model survives**

Navigate back out of AI Chat to the model list (same action that caused a 503 in the previous plan's Task 7 Step 7). Repeat Step 2's curl.
Expected: `/v1/models` still reports the same model chat was just using — **no 503**. This is the fix; contrast directly with the earlier, now-superseded behavior.

- [ ] **Step 6: Confirm toggling off releases everything**

Turn off "Expose local API server" in Settings. Confirm via `adb logcat` that the foreground service's `onDestroy` runs (no lingering notification) and `adb shell curl http://127.0.0.1:8080/v1/models` fails to connect (connection refused, port no longer listening).

- [ ] **Step 7: Record the outcome**

Update the spec's Status line to "Implemented, verified on-device <date>" with a short results summary, matching the previous plan's Task 7 Step 9 pattern.

```bash
git add docs/superpowers/specs/2026-09-17-api-server-mode-design.md
git commit -m "docs: record manual verification of API server mode"
```

---

## Self-review notes

- **Spec coverage:** Goals (independent load, survive screen close, model picker) → Tasks 1, 2, 5, 6. Non-goal (no true dual-engine isolation) respected — Task 2's guard only ever prevents *one* shared session from being torn down; it never loads two. Error handling table (no downloaded model, deleted mid-serve, picker changed while on) → Task 5 Steps 2 and 4, and the existing re-`initialize`-on-change path in Task 6 Step 1. Testing section → Tasks 1–4 unit tests + Task 7 manual script.
- **Type consistency:** `ApiServerSessionHold.heldModelName` (Task 1) is set only by `LocalApiForegroundService` (Task 5) and read only by `DefaultAgentRuntimeExecutor.cleanUp()` (Task 2) — single writer during normal operation, matching the spec's synchronous-guard design. `ModelCatalogCache.models` (Task 3) is written only by `ModelManagerViewModel` and read only by `LocalApiForegroundService` — no other writer was introduced.
- **Known gap intentionally deferred:** the "picker changed while server is on triggers reload" path (Task 6 Step 1's `setLocalApiServerSelectedModel`) restarts the whole service via `startForegroundService`, which briefly drops and reopens the Ktor listener rather than swapping the model in place. Acceptable per the spec's error-handling table ("existing in-flight requests against the old model may fail... acceptable, rare").
