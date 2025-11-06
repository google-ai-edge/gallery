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

package com.neuralforge.mobile.customtasks.examplecustomtask

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.runtime.Composable
import com.neuralforge.mobile.customtasks.common.CustomTask
import com.neuralforge.mobile.customtasks.common.CustomTaskData
import com.neuralforge.mobile.data.CategoryInfo
import com.neuralforge.mobile.data.Model
import com.neuralforge.mobile.data.Task
import com.neuralforge.mobile.ui.modelmanager.ModelManagerViewModel
import java.io.File
import javax.inject.Inject
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * An example implementation of a `CustomTask` that demonstrates how to display the content of a
 * text-based model file.
 *
 * This class provides two primary examples of how to configure models:
 * 1. A "Local model" that expects a file (`model.txt`) to be manually pushed to the device. The
 *    `localFileRelativeDirPathOverride` field is used to specify this behavior.
 * 2. A "Remote model" that downloads a file (`README.md`) from a URL. The `url` and
 *    `downloadFileName` fields are used for this configuration.
 *
 * It showcases the following key functionalities:
 * - Task Definition: The `task` property defines the task's metadata, including its name ("Model
 *   Viewer"), category, description, and the list of models it supports.
 * - Model Initialization: The `initializeModelFn` function shows how to read the content of the
 *   model file (either local or downloaded) and store it in a custom
 *   `ExampleCustomTaskModelInstance`. It also demonstrates how to access model-specific
 *   configurations, such as `maxCharCount`, which can be updated by the user.
 * - Model Cleanup: The `cleanUpModelFn` function is a simple example of how to release resources by
 *   nullifying the model instance.
 * - UI Integration: The `MainScreen` composable provides the UI for the task, displaying the
 *   model's content. It uses a `ViewModel` to manage UI state, such as text color, and reacts to
 *   changes in model configurations, like font size.
 */
class ExampleCustomTask @Inject constructor() : CustomTask {
  override val task: Task =
    Task(
      id = "example_custom_task",
      label = "Model Viewer",
      category = CategoryInfo(id = "example", label = "Example"),
      icon = Icons.Outlined.TextFields,
      description =
        "This example task demonstrates a custom task that reads and displays the content of a " +
          "model file (with text content for demonstration purpose). The \"models\" listed " +
          "below are configured in different ways in terms of how the model file is provided " +
          "(pushed to device manually, vs downloaded from internet).",
      docUrl =
        "https://github.com/google-ai-edge/gallery/Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/common/CustomTask.kt",
      sourceCodeUrl =
        "https://github.com/google-ai-edge/gallery/Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/examplecustomtask/ExampleCustomTask.kt",
      models =
        mutableListOf(
          Model(
            name = "Local model",
            info =
              "Expects to read the model file `model.txt` manually pushed to `{ext_files_dir}/example_task/`.",
            localFileRelativeDirPathOverride = "example_task/",
            bestForTaskIds = listOf("example_custom_task"),
            configs = EXAMPLE_CUSTOM_TASK_CONFIGS,
          ),
          Model(
            name = "Remote model",
            info =
              "Downloads the model file (a README.md file for demonstration purpose) from internet.",
            url =
              "https://raw.githubusercontent.com/google-ai-edge/gallery/refs/heads/main/README.md",
            sizeInBytes = 3798L,
            downloadFileName = "README.md",
            configs = EXAMPLE_CUSTOM_TASK_CONFIGS,
          ),
        ),
    )

  override fun initializeModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    onDone: (String) -> Unit,
  ) {
    coroutineScope.launch(Dispatchers.IO) {
      model.instance = null
      try {
        // Read model file content.
        val file =
          // Remote model
          if (model.localFileRelativeDirPathOverride.isEmpty())
            File(model.getPath(context = context))
          // Local model
          else File(model.getPath(context = context, fileName = "model.txt"))
        var content = file.readText()

        // Use the value from model's configuration to cap the max number of characters for the
        // content.
        val maxCharCount =
          model.getIntConfigValue(key = EXAMPLE_CUSTOM_TASK_CONFIG_KEY_MAX_CHAR_COUNT)
        content = content.substring(0, min(content.length, maxCharCount))

        // Set model instance.
        //
        // For this example, we're just storing the text content as a data class instance.
        // In a real application, this instance would be an object that provides
        // inference capabilities, such as a TFLite interpreter or a pointer to a model
        // loaded in memory.
        model.instance = ExampleCustomTaskModelInstance(content = content)

        // Simulate long initialization time.
        delay(1500)

        // Notify the initialization is done.
        onDone("")
      } catch (e: Exception) {
        // Handle errors.
        onDone(e.message ?: "Failed to read model file")
      }
    }
  }

  override fun cleanUpModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    onDone: () -> Unit,
  ) {
    // In a real application, this is where you would release resources
    // associated with the model, such as closing a TFLite interpreter
    // or freeing up model memory. For this example, we simply set the
    // instance to null.
    model.instance = null

    // Notify the cleanup is done.
    onDone()
  }

  @Composable
  override fun MainScreen(data: Any) {
    // The ModelManagerViewModel is essential for accessing the state of the currently
    // selected model, its initialization status, etc.
    // This allows the UI to react to changes, such as displaying the model's content
    // only after it has been successfully initialized.
    val myData = data as CustomTaskData
    val modelManagerViewModel: ModelManagerViewModel = myData.modelManagerViewModel

    ExampleCustomTaskScreen(modelManagerViewModel = modelManagerViewModel)
  }
}
