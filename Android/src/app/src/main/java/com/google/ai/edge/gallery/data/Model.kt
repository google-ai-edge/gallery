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

package com.google.ai.edge.gallery.data

import android.content.Context
import java.io.File

data class ModelDataFile(
  val name: String,
  val url: String,
  val downloadFileName: String,
  val sizeInBytes: Long,
)

const val IMPORTS_DIR = "__imports"
private val NORMALIZE_NAME_REGEX = Regex("[^a-zA-Z0-9]")

data class PromptTemplate(val title: String, val description: String, val prompt: String)

/**
 * A model for a task (see [Task]).
 *
 * A task can have multiple models. For example, a task might be "LLM Chat", and it might have
 * models such as Gemma2, Gemma3, etc.
 */
data class Model(
  /**
   * The name of the model.
   *
   * This field is used to uniquely identify this model among all the tasks.
   *
   * IMPORTANT: it shouldn't contain "/" character.
   */
  val name: String,

  /**
   * The display name of the model, for display purpose.
   *
   * If this field is not set, the `name` field above will be used as the default display name.
   */
  val displayName: String = "",

  /**
   * (optional)
   *
   * A description or information about the model (Markdown supported).
   *
   * Displayed in the expanded model info card.
   */
  val info: String = "",

  /**
   * (optional)
   *
   * A list of configurable parameters for the model.
   *
   * If set, a gear icon appears on the right side of the model main screen's app bar. When
   * selected, a dialog pops up, allowing users to update the model's configurations.
   *
   * See [Config] for more details
   */
  val configs: List<Config> = listOf(),

  /**
   * (optional)
   *
   * The url to jump to when clicking "learn more" in model's info card.
   */
  val learnMoreUrl: String = "",

  /**
   * (optional)
   *
   * The task type ids that this model is best for.
   *
   * When set, the model's info card is pinned to the top of the model list when the corresponding
   * task is selected, expanded by default, and displays a "best overall" banner.
   *
   * Each task should only have one such model.
   */
  val bestForTaskIds: List<String> = listOf(),

  /**
   * (optional)
   *
   * The minimum device memory in GB to run the model.
   *
   * If set, a warning dialog will be shown when user trying to download the model or enter the
   * model screen.
   */
  val minDeviceMemoryInGb: Int? = null,

  //////////////////////////////////////////////////////////////////////////////////////////////////
  // Fill in the following fields if the model file needs to be downloaded from internet.
  //
  // If you want to manually manage model files without downloading them from internet, set the
  // `localFilePathOverride` field below.

  /**
   * The URL to download the model from.
   *
   * If the url is from HuggingFace, we will automatically prompt users to fetch access token if the
   * model is gated.
   */
  val url: String = "",

  /**
   * The size of the model file in bytes.
   *
   * This will be used to calculate download progress.
   */
  val sizeInBytes: Long = 0L,

  /**
   * The name of the downloaded model file.
   *
   * It will be used to define the file path on local device to store the downloaded model.
   * {context.getExternalFilesDir}/{normalizedName}/{version}/{downloadFileName}
   */
  val downloadFileName: String = "_",

  /**
   * (optional)
   *
   * The version of the model.
   *
   * It will be used to define the file path on local device to store the downloaded model.
   * {context.getExternalFilesDir}/{normalizedName}/{version}/{downloadFileName}
   */
  val version: String = "_",

  /**
   * (optional, experimental)
   *
   * A list of additional data files required by the model.
   */
  val extraDataFiles: List<ModelDataFile> = listOf(),

  // End of model download related fields.
  //////////////////////////////////////////////////////////////////////////////////////////////////

  /**
   * Set this to a relative path pointing to a dir (e.g., my_model/local_dir/) if you want to
   * manually manage model files instead of downloading them. This dir is relative to the app's
   * "External Files Directory", which is: /storage/emulated/0/Android/data/<app_id>/files/.
   *
   * The <app_id> depends on how the app was built:
   * - `com.google.aiedge.gallery` for builds from the GitHub source.
   * - `com.google.ai.edge.gallery` for other builds (Play store, internal, etc).
   *
   * For example, if this field is set to "my_model/local_dir/", then the location you should push
   * files to is (assuming non-github builds):
   *
   * /storage/emulated/0/Android/data/com.google.ai.edge.gallery/files/my_model/local_dir/
   *
   * You can get the full path to a specific file within your code using `Model.getPath(Context,
   * fileNameToGet)`.
   *
   * Using this field is recommended when:
   * - Your model files are not publicly accessible on the internet (e.g. private models).
   * - Your "model" or experience requires multiple files. Manually pushing these files to the
   *   device and using Model.getPath() for each one is often simpler than downloading them,
   *   especially for demos.
   */
  val localFileRelativeDirPathOverride: String = "",

  /**
   * When set, the app will try to use this path to find the model file.
   *
   * For testing purpose only.
   */
  val localModelFilePathOverride: String = "",

  // The following fields are only used for built-in tasks. Can ignore if you are creating your own
  // custom tasks.
  //

  /** Whether to show the "run again" button in the UI. */
  val showRunAgainButton: Boolean = true,

  /** Whether to show the "benchmark" button in the UI. */
  val showBenchmarkButton: Boolean = true,

  /** Indicates whether the model is a zip file. */
  val isZip: Boolean = false,

  /** The name of the directory to unzip the model to (if it's a zip file). */
  val unzipDir: String = "",

  /** The prompt templates for the model (only for LLM). */
  val llmPromptTemplates: List<PromptTemplate> = listOf(),

  /** Whether the LLM model supports image input. */
  val llmSupportImage: Boolean = false,

  /** Whether the LLM model supports audio input. */
  val llmSupportAudio: Boolean = false,

  /** Whether the LLM model supports agents. */
  val llmSupportAgents: Boolean = false,

  /** Whether the model is imported or not. */
  val imported: Boolean = false,

  // The following fields are managed by the app. Don't need to set manually.
  //
  var normalizedName: String = "",
  var instance: Any? = null,
  var initializing: Boolean = false,
  // TODO(jingjin): use a "queue" system to manage model init and cleanup.
  var cleanUpAfterInit: Boolean = false,
  var configValues: Map<String, Any> = mapOf(),
  var totalBytes: Long = 0L,
  var accessToken: String? = null,
) {
  init {
    normalizedName = NORMALIZE_NAME_REGEX.replace(name, "_")
  }

  fun preProcess() {
    val configValues: MutableMap<String, Any> = mutableMapOf()
    for (config in this.configs) {
      configValues[config.key.label] = config.defaultValue
    }
    this.configValues = configValues
    this.totalBytes = this.sizeInBytes + this.extraDataFiles.sumOf { it.sizeInBytes }
  }

  fun getPath(context: Context, fileName: String = downloadFileName): String {
    if (imported) {
      return listOf(context.getExternalFilesDir(null)?.absolutePath ?: "", fileName)
        .joinToString(File.separator)
    }

    if (localModelFilePathOverride.isNotEmpty()) {
      return localModelFilePathOverride
    }

    if (localFileRelativeDirPathOverride.isNotEmpty()) {
      return listOf(
          context.getExternalFilesDir(null)?.absolutePath ?: "",
          localFileRelativeDirPathOverride,
          fileName,
        )
        .joinToString(File.separator)
    }

    val baseDir =
      listOf(context.getExternalFilesDir(null)?.absolutePath ?: "", normalizedName, version)
        .joinToString(File.separator)
    return if (this.isZip && this.unzipDir.isNotEmpty()) {
      listOf(baseDir, this.unzipDir).joinToString(File.separator)
    } else {
      listOf(baseDir, fileName).joinToString(File.separator)
    }
  }

  fun getIntConfigValue(key: ConfigKey, defaultValue: Int = 0): Int {
    return getTypedConfigValue(key = key, valueType = ValueType.INT, defaultValue = defaultValue)
      as Int
  }

  fun getFloatConfigValue(key: ConfigKey, defaultValue: Float = 0.0f): Float {
    return getTypedConfigValue(key = key, valueType = ValueType.FLOAT, defaultValue = defaultValue)
      as Float
  }

  fun getBooleanConfigValue(key: ConfigKey, defaultValue: Boolean = false): Boolean {
    return getTypedConfigValue(
      key = key,
      valueType = ValueType.BOOLEAN,
      defaultValue = defaultValue,
    )
      as Boolean
  }

  fun getStringConfigValue(key: ConfigKey, defaultValue: String = ""): String {
    return getTypedConfigValue(key = key, valueType = ValueType.STRING, defaultValue = defaultValue)
      as String
  }

  fun getExtraDataFile(name: String): ModelDataFile? {
    return extraDataFiles.find { it.name == name }
  }

  private fun getTypedConfigValue(key: ConfigKey, valueType: ValueType, defaultValue: Any): Any {
    return convertValueToTargetType(
      value = configValues.getOrDefault(key.label, defaultValue),
      valueType = valueType,
    )
  }
}

enum class ModelDownloadStatusType {
  NOT_DOWNLOADED,
  PARTIALLY_DOWNLOADED,
  IN_PROGRESS,
  UNZIPPING,
  SUCCEEDED,
  FAILED,
}

data class ModelDownloadStatus(
  val status: ModelDownloadStatusType,
  val totalBytes: Long = 0,
  val receivedBytes: Long = 0,
  val errorMessage: String = "",
  val bytesPerSecond: Long = 0,
  val remainingMs: Long = 0,
)

////////////////////////////////////////////////////////////////////////////////////////////////////
// Configs.

val MOBILENET_CONFIGS: List<Config> =
  listOf(
    NumberSliderConfig(
      key = ConfigKeys.MAX_RESULT_COUNT,
      sliderMin = 1f,
      sliderMax = 5f,
      defaultValue = 3f,
      valueType = ValueType.INT,
    ),
    BooleanSwitchConfig(key = ConfigKeys.USE_GPU, defaultValue = false),
  )

val IMAGE_GENERATION_CONFIGS: List<Config> =
  listOf(
    NumberSliderConfig(
      key = ConfigKeys.ITERATIONS,
      sliderMin = 5f,
      sliderMax = 50f,
      defaultValue = 10f,
      valueType = ValueType.INT,
      needReinitialization = false,
    )
  )

const val TEXT_CLASSIFICATION_INFO =
  "Model is trained on movie reviews dataset. Type a movie review below and see the scores of positive or negative sentiment."

const val TEXT_CLASSIFICATION_LEARN_MORE_URL =
  "https://ai.google.dev/edge/mediapipe/solutions/text/text_classifier"

const val IMAGE_CLASSIFICATION_INFO = ""

const val IMAGE_CLASSIFICATION_LEARN_MORE_URL = "https://ai.google.dev/edge/litert/android"

const val IMAGE_GENERATION_INFO =
  "Powered by [MediaPipe Image Generation API](https://ai.google.dev/edge/mediapipe/solutions/vision/image_generator/android)"

val MODEL_TEXT_CLASSIFICATION_MOBILEBERT: Model =
  Model(
    name = "MobileBert",
    downloadFileName = "bert_classifier.tflite",
    url =
      "https://storage.googleapis.com/mediapipe-models/text_classifier/bert_classifier/float32/latest/bert_classifier.tflite",
    sizeInBytes = 25707538L,
    info = TEXT_CLASSIFICATION_INFO,
    learnMoreUrl = TEXT_CLASSIFICATION_LEARN_MORE_URL,
  )

val MODEL_TEXT_CLASSIFICATION_AVERAGE_WORD_EMBEDDING: Model =
  Model(
    name = "Average word embedding",
    downloadFileName = "average_word_classifier.tflite",
    url =
      "https://storage.googleapis.com/mediapipe-models/text_classifier/average_word_classifier/float32/latest/average_word_classifier.tflite",
    sizeInBytes = 775708L,
    info = TEXT_CLASSIFICATION_INFO,
  )

val MODEL_IMAGE_CLASSIFICATION_MOBILENET_V1: Model =
  Model(
    name = "Mobilenet V1",
    downloadFileName = "mobilenet_v1.tflite",
    url = "https://storage.googleapis.com/tfweb/app_gallery_models/mobilenet_v1.tflite",
    sizeInBytes = 16900760L,
    extraDataFiles =
      listOf(
        ModelDataFile(
          name = "labels",
          url =
            "https://raw.githubusercontent.com/leferrad/tensorflow-mobilenet/refs/heads/master/imagenet/labels.txt",
          downloadFileName = "mobilenet_labels_v1.txt",
          sizeInBytes = 21685L,
        )
      ),
    configs = MOBILENET_CONFIGS,
    info = IMAGE_CLASSIFICATION_INFO,
    learnMoreUrl = IMAGE_CLASSIFICATION_LEARN_MORE_URL,
  )

val MODEL_IMAGE_CLASSIFICATION_MOBILENET_V2: Model =
  Model(
    name = "Mobilenet V2",
    downloadFileName = "mobilenet_v2.tflite",
    url = "https://storage.googleapis.com/tfweb/app_gallery_models/mobilenet_v2.tflite",
    sizeInBytes = 13978596L,
    extraDataFiles =
      listOf(
        ModelDataFile(
          name = "labels",
          url =
            "https://raw.githubusercontent.com/leferrad/tensorflow-mobilenet/refs/heads/master/imagenet/labels.txt",
          downloadFileName = "mobilenet_labels_v2.txt",
          sizeInBytes = 21685L,
        )
      ),
    configs = MOBILENET_CONFIGS,
    info = IMAGE_CLASSIFICATION_INFO,
  )

val MODEL_IMAGE_GENERATION_STABLE_DIFFUSION: Model =
  Model(
    name = "Stable diffusion",
    downloadFileName = "sd15.zip",
    isZip = true,
    unzipDir = "sd15",
    url = "https://storage.googleapis.com/tfweb/app_gallery_models/sd15.zip",
    sizeInBytes = 1906219565L,
    showRunAgainButton = false,
    showBenchmarkButton = false,
    info = IMAGE_GENERATION_INFO,
    configs = IMAGE_GENERATION_CONFIGS,
    learnMoreUrl = "https://huggingface.co/litert-community",
  )

val EMPTY_MODEL: Model =
  Model(name = "empty", downloadFileName = "empty.tflite", url = "", sizeInBytes = 0L)

////////////////////////////////////////////////////////////////////////////////////////////////////
// Model collections for different tasks.

val MODELS_TEXT_CLASSIFICATION: MutableList<Model> =
  mutableListOf(
    MODEL_TEXT_CLASSIFICATION_MOBILEBERT,
    MODEL_TEXT_CLASSIFICATION_AVERAGE_WORD_EMBEDDING,
  )

val MODELS_IMAGE_CLASSIFICATION: MutableList<Model> =
  mutableListOf(MODEL_IMAGE_CLASSIFICATION_MOBILENET_V1, MODEL_IMAGE_CLASSIFICATION_MOBILENET_V2)

val MODELS_IMAGE_GENERATION: MutableList<Model> =
  mutableListOf(MODEL_IMAGE_GENERATION_STABLE_DIFFUSION)
