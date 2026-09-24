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
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.RuntimeType
import com.google.ai.edge.gallery.runtime.CleanUpListener
import com.google.ai.edge.gallery.runtime.LlmModelHelper
import com.google.ai.edge.gallery.runtime.ResultListener
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.ToolProvider
import com.google.async.coroutines.testing.doBlocking
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ModelManagerTest {

  private lateinit var context: Context
  private lateinit var modelManager: ModelManager

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    modelManager = ModelManager(context)
  }

  @Test
  fun resolveModel_aicoreName_returnsAicoreModel() {
    val model =
      modelManager.resolveModel(
        "some-aicore-model",
        supportImage = true,
        supportAudio = false,
        accelerator = "GPU",
      )
    assertThat(model).isNotNull()
    assertThat(model!!.runtimeType).isEqualTo(RuntimeType.AICORE)
    assertThat(model.llmSupportImage).isTrue()
    assertThat(model.llmSupportAudio).isFalse()
    assertThat(model.configValues[ConfigKeys.ACCELERATOR.label]).isEqualTo("GPU")
  }

  @Test
  fun resolveModel_localPath_returnsLitertModelWithPath() {
    val model =
      modelManager.resolveModel(
        "/path/to/model.bin",
        supportImage = true,
        supportAudio = true,
        accelerator = "GPU",
      )
    assertThat(model).isNotNull()
    assertThat(model!!.runtimeType).isEqualTo(RuntimeType.LITERT_LM)
    assertThat(model.localModelFilePathOverride).isEqualTo("/path/to/model.bin")
    assertThat(model.llmSupportImage).isTrue()
    assertThat(model.llmSupportAudio).isTrue()
    assertThat(model.configValues[ConfigKeys.ACCELERATOR.label]).isEqualTo("GPU")
  }

  @Test
  fun preInitModel_nullHelper_returnsEarly() {
    val testManager =
      object : ModelManager(context) {
        override fun getHelperForModelName(modelName: String): LlmModelHelper? {
          return null
        }
      }
    // Should not crash, just returns early
    testManager.preInitModel("test-pre-init", false, false, "CPU")
  }

  @Test
  fun getOrInitModel_success_cachesModel() = doBlocking {
    val helper = FakeLlmModelHelper(initDelayMs = 10)
    val modelName = "test-model"

    val model =
      modelManager.getOrInitModel(
        modelName,
        helper,
        supportImage = false,
        supportAudio = false,
        accelerator = "CPU",
      )

    assertThat(model).isNotNull()
    assertThat(helper.initCallCount.get()).isEqualTo(1)

    // Second call should return cached model without calling initialize again
    val model2 =
      modelManager.getOrInitModel(
        modelName,
        helper,
        supportImage = false,
        supportAudio = false,
        accelerator = "CPU",
      )
    assertThat(model2).isSameInstanceAs(model)
    assertThat(helper.initCallCount.get()).isEqualTo(1)
  }

  @Test
  fun getOrInitModel_concurrentRequests_initializesOnlyOnce() = doBlocking {
    val helper = FakeLlmModelHelper(initDelayMs = 50)
    val modelName = "concurrent-model"

    // Launch 3 concurrent requests
    val deferreds = mutableListOf<Deferred<Model?>>()
    val scope = CoroutineScope(Dispatchers.Default)

    for (i in 1..3) {
      deferreds.add(
        scope.async {
          modelManager.getOrInitModel(
            modelName,
            helper,
            supportImage = false,
            supportAudio = false,
            accelerator = "CPU",
          )
        }
      )
    }

    val results = deferreds.awaitAll()

    // Verify all got the same non-null model
    assertThat(results[0]).isNotNull()
    assertThat(results[1]).isSameInstanceAs(results[0])
    assertThat(results[2]).isSameInstanceAs(results[0])

    // Verify initialize was only called once
    assertThat(helper.initCallCount.get()).isEqualTo(1)
  }

  @Test
  fun getOrInitModel_failure_doesNotCache() = doBlocking {
    val helper = FakeLlmModelHelper(shouldFail = true)
    val modelName = "failing-model"

    val model =
      modelManager.getOrInitModel(
        modelName,
        helper,
        supportImage = false,
        supportAudio = false,
        accelerator = "CPU",
      )
    assertThat(model).isNull()
    assertThat(helper.initCallCount.get()).isEqualTo(1)

    // Second call should try to initialize again because first failed
    val model2 =
      modelManager.getOrInitModel(
        modelName,
        helper,
        supportImage = false,
        supportAudio = false,
        accelerator = "CPU",
      )
    assertThat(model2).isNull()
    assertThat(helper.initCallCount.get()).isEqualTo(2)
  }

  @Test
  fun preInitModel_startsInitializationInBackground() = doBlocking {
    val fakeHelper = FakeLlmModelHelper(initDelayMs = 10)
    val testManager =
      object : ModelManager(context) {
        override fun getHelperForModelName(modelName: String): LlmModelHelper? {
          return fakeHelper
        }
      }

    testManager.preInitModel(
      "test-pre-init",
      supportImage = false,
      supportAudio = false,
      accelerator = "CPU",
    )

    // Wait a bit for the background launch to complete
    delay(50)

    assertThat(fakeHelper.initCallCount.get()).isEqualTo(1)
  }

  // A simple fake implementation of LlmModelHelper for testing
  class FakeLlmModelHelper(
    private val initDelayMs: Long = 0,
    private val shouldFail: Boolean = false,
  ) : LlmModelHelper {
    val initCallCount = AtomicInteger(0)

    override fun initialize(
      context: Context,
      model: Model,
      taskId: String,
      supportImage: Boolean,
      supportAudio: Boolean,
      onDone: (String) -> Unit,
      systemInstruction: Contents?,
      tools: List<ToolProvider>,
      enableConversationConstrainedDecoding: Boolean,
      coroutineScope: CoroutineScope?,
    ) {
      initCallCount.incrementAndGet()
      val scope = coroutineScope ?: CoroutineScope(Dispatchers.Default)
      scope.launch {
        if (initDelayMs > 0) {
          delay(initDelayMs)
        }
        if (shouldFail) {
          onDone("Error: Simulated failure")
        } else {
          // Simulate the populated instance
          model.instance = Any() // just a dummy object
          onDone("Feature is available")
        }
      }
    }

    override fun resetConversation(
      model: Model,
      supportImage: Boolean,
      supportAudio: Boolean,
      systemInstruction: Contents?,
      tools: List<ToolProvider>,
      enableConversationConstrainedDecoding: Boolean,
      initialMessages: List<Message>,
    ) {}

    override fun cleanUp(model: Model, onDone: () -> Unit) {
      onDone()
    }

    override fun runInference(
      model: Model,
      input: String,
      resultListener: ResultListener,
      cleanUpListener: CleanUpListener,
      onError: (message: String) -> Unit,
      images: List<Bitmap>,
      audioClips: List<ByteArray>,
      coroutineScope: CoroutineScope?,
      extraContext: Map<String, String>?,
    ) {
      // Simulate instantaneous inference completion to prevent tests from hanging
      resultListener("mock response", true, null)
    }

    override fun stopResponse(model: Model) {}
  }
}
