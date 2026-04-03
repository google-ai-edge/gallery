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

package com.google.ai.edge.gallery.data

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelTest {

  // ---------------------------------------------------------------------------
  // normalizedName
  // ---------------------------------------------------------------------------

  @Test
  fun `normalizedName replaces hyphens and slashes with underscores`() {
    val model = Model(name = "my-model/v1")
    assertEquals("my_model_v1", model.normalizedName)
  }

  @Test
  fun `normalizedName replaces spaces and dots with underscores`() {
    val model = Model(name = "Gemma 3n E2B 4.0")
    assertEquals("Gemma_3n_E2B_4_0", model.normalizedName)
  }

  @Test
  fun `normalizedName leaves alphanumeric names unchanged`() {
    val model = Model(name = "Gemma3")
    assertEquals("Gemma3", model.normalizedName)
  }

  @Test
  fun `normalizedName replaces all non-alphanumeric characters`() {
    val model = Model(name = "my-model/v1.0 test!")
    assertEquals("my_model_v1_0_test_", model.normalizedName)
  }

  // ---------------------------------------------------------------------------
  // preProcess — configValues
  // ---------------------------------------------------------------------------

  @Test
  fun `preProcess populates configValues with slider defaults`() {
    val model = Model(
      name = "test",
      configs = listOf(
        NumberSliderConfig(
          key = ConfigKeys.TOPK,
          sliderMin = 5f,
          sliderMax = 100f,
          defaultValue = 40f,
          valueType = ValueType.INT,
        ),
        NumberSliderConfig(
          key = ConfigKeys.TEMPERATURE,
          sliderMin = 0f,
          sliderMax = 2f,
          defaultValue = 0.7f,
          valueType = ValueType.FLOAT,
        ),
      ),
    )
    model.preProcess()
    assertEquals(40f, model.configValues[ConfigKeys.TOPK.label])
    assertEquals(0.7f, model.configValues[ConfigKeys.TEMPERATURE.label])
  }

  @Test
  fun `preProcess populates configValues with boolean switch defaults`() {
    val model = Model(
      name = "test",
      configs = listOf(
        BooleanSwitchConfig(key = ConfigKeys.ENABLE_THINKING, defaultValue = false),
        BooleanSwitchConfig(key = ConfigKeys.USE_GPU, defaultValue = true),
      ),
    )
    model.preProcess()
    assertEquals(false, model.configValues[ConfigKeys.ENABLE_THINKING.label])
    assertEquals(true, model.configValues[ConfigKeys.USE_GPU.label])
  }

  @Test
  fun `preProcess with no configs results in empty configValues`() {
    val model = Model(name = "test")
    model.preProcess()
    assertEquals(emptyMap<String, Any>(), model.configValues)
  }

  // ---------------------------------------------------------------------------
  // preProcess — totalBytes
  // ---------------------------------------------------------------------------

  @Test
  fun `preProcess totalBytes equals sizeInBytes when no extra files`() {
    val model = Model(name = "test", sizeInBytes = 1_000_000L)
    model.preProcess()
    assertEquals(1_000_000L, model.totalBytes)
  }

  @Test
  fun `preProcess totalBytes sums model and all extra data files`() {
    val model = Model(
      name = "test",
      sizeInBytes = 1_000L,
      extraDataFiles = listOf(
        ModelDataFile(name = "tokenizer", url = "url1", downloadFileName = "tok.bin", sizeInBytes = 200L),
        ModelDataFile(name = "vocab", url = "url2", downloadFileName = "vocab.bin", sizeInBytes = 300L),
      ),
    )
    model.preProcess()
    assertEquals(1_500L, model.totalBytes)
  }

  // ---------------------------------------------------------------------------
  // getPath
  // ---------------------------------------------------------------------------

  @Test
  fun `getPath returns localModelFilePathOverride directly without using context`() {
    val model = Model(name = "test", localModelFilePathOverride = "/override/path/model.bin")
    // Context is never called — no stubbing needed
    val context = mockk<Context>()
    assertEquals("/override/path/model.bin", model.getPath(context))
  }

  @Test
  fun `getPath for imported model returns externalFilesDir plus downloadFileName`() {
    val model = Model(name = "test", imported = true, downloadFileName = "imported.tflite")
    val context = mockk<Context>()
    every { context.getExternalFilesDir(null) } returns File("/fake/ext")
    assertEquals("/fake/ext/imported.tflite", model.getPath(context))
  }

  @Test
  fun `getPath for imported model with custom fileName uses that fileName`() {
    val model = Model(name = "test", imported = true, downloadFileName = "model.tflite")
    val context = mockk<Context>()
    every { context.getExternalFilesDir(null) } returns File("/fake/ext")
    assertEquals("/fake/ext/custom.bin", model.getPath(context, fileName = "custom.bin"))
  }

  @Test
  fun `getPath with localFileRelativeDirPathOverride builds path correctly`() {
    val model = Model(
      name = "test",
      downloadFileName = "model.bin",
      localFileRelativeDirPathOverride = "my_model/local_dir",
    )
    val context = mockk<Context>()
    every { context.getExternalFilesDir(null) } returns File("/fake/ext")
    assertEquals("/fake/ext/my_model/local_dir/model.bin", model.getPath(context))
  }

  @Test
  fun `getPath default path uses normalizedName and version`() {
    val model = Model(name = "my-model", version = "v2", downloadFileName = "model.tflite")
    val context = mockk<Context>()
    every { context.getExternalFilesDir(null) } returns File("/fake/ext")
    assertEquals("/fake/ext/my_model/v2/model.tflite", model.getPath(context))
  }

  @Test
  fun `getPath for zip with unzipDir returns unzipDir path`() {
    val model = Model(
      name = "my-model",
      version = "v1",
      downloadFileName = "archive.zip",
      isZip = true,
      unzipDir = "extracted_dir",
    )
    val context = mockk<Context>()
    every { context.getExternalFilesDir(null) } returns File("/fake/ext")
    assertEquals("/fake/ext/my_model/v1/extracted_dir", model.getPath(context))
  }

  @Test
  fun `getPath for zip without unzipDir falls back to downloadFileName`() {
    val model = Model(
      name = "my-model",
      version = "v1",
      downloadFileName = "archive.zip",
      isZip = true,
      unzipDir = "",
    )
    val context = mockk<Context>()
    every { context.getExternalFilesDir(null) } returns File("/fake/ext")
    assertEquals("/fake/ext/my_model/v1/archive.zip", model.getPath(context))
  }

  @Test
  fun `getPath returns empty-string-based path when externalFilesDir is null`() {
    val model = Model(name = "test", version = "v1", downloadFileName = "model.bin")
    val context = mockk<Context>()
    every { context.getExternalFilesDir(null) } returns null
    // Should not throw; null is replaced with empty string
    val path = model.getPath(context)
    assertEquals(File.separator + "test" + File.separator + "v1" + File.separator + "model.bin", path)
  }

  // ---------------------------------------------------------------------------
  // getIntConfigValue / getFloatConfigValue / getBooleanConfigValue
  // ---------------------------------------------------------------------------

  @Test
  fun `getIntConfigValue returns casted int after preProcess`() {
    val model = Model(
      name = "test",
      configs = listOf(
        NumberSliderConfig(
          key = ConfigKeys.TOPK,
          sliderMin = 5f,
          sliderMax = 100f,
          defaultValue = 42f,
          valueType = ValueType.INT,
        ),
      ),
    )
    model.preProcess()
    assertEquals(42, model.getIntConfigValue(ConfigKeys.TOPK))
  }

  @Test
  fun `getIntConfigValue returns supplied defaultValue when key is absent`() {
    val model = Model(name = "test")
    model.preProcess()
    assertEquals(99, model.getIntConfigValue(ConfigKeys.TOPK, defaultValue = 99))
  }

  @Test
  fun `getFloatConfigValue returns correct float after preProcess`() {
    val model = Model(
      name = "test",
      configs = listOf(
        NumberSliderConfig(
          key = ConfigKeys.TEMPERATURE,
          sliderMin = 0f,
          sliderMax = 2f,
          defaultValue = 0.8f,
          valueType = ValueType.FLOAT,
        ),
      ),
    )
    model.preProcess()
    assertEquals(0.8f, model.getFloatConfigValue(ConfigKeys.TEMPERATURE))
  }

  @Test
  fun `getFloatConfigValue returns supplied defaultValue when key is absent`() {
    val model = Model(name = "test")
    model.preProcess()
    assertEquals(1.5f, model.getFloatConfigValue(ConfigKeys.TEMPERATURE, defaultValue = 1.5f))
  }

  @Test
  fun `getBooleanConfigValue returns true when default is true`() {
    val model = Model(
      name = "test",
      configs = listOf(BooleanSwitchConfig(key = ConfigKeys.ENABLE_THINKING, defaultValue = true)),
    )
    model.preProcess()
    assertEquals(true, model.getBooleanConfigValue(ConfigKeys.ENABLE_THINKING))
  }

  @Test
  fun `getBooleanConfigValue returns false when default is false`() {
    val model = Model(
      name = "test",
      configs = listOf(BooleanSwitchConfig(key = ConfigKeys.ENABLE_THINKING, defaultValue = false)),
    )
    model.preProcess()
    assertEquals(false, model.getBooleanConfigValue(ConfigKeys.ENABLE_THINKING))
  }

  @Test
  fun `getBooleanConfigValue returns supplied defaultValue when key is absent`() {
    val model = Model(name = "test")
    model.preProcess()
    assertEquals(true, model.getBooleanConfigValue(ConfigKeys.ENABLE_THINKING, defaultValue = true))
  }

  // ---------------------------------------------------------------------------
  // getExtraDataFile
  // ---------------------------------------------------------------------------

  @Test
  fun `getExtraDataFile returns correct file by name`() {
    val tokenizerFile = ModelDataFile(name = "tokenizer", url = "url1", downloadFileName = "tok.bin", sizeInBytes = 100L)
    val model = Model(
      name = "test",
      extraDataFiles = listOf(
        tokenizerFile,
        ModelDataFile(name = "vocab", url = "url2", downloadFileName = "vocab.bin", sizeInBytes = 50L),
      ),
    )
    assertEquals(tokenizerFile, model.getExtraDataFile("tokenizer"))
  }

  @Test
  fun `getExtraDataFile returns null for unknown name`() {
    val model = Model(name = "test", extraDataFiles = listOf())
    assertNull(model.getExtraDataFile("nonexistent"))
  }
}
