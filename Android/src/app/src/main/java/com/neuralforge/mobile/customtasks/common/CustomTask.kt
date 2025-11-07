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

package com.neuralforge.mobile.customtasks.common

import android.content.Context
import androidx.compose.runtime.Composable
import com.neuralforge.mobile.data.Model
import com.neuralforge.mobile.data.Task
import kotlinx.coroutines.CoroutineScope

/**
 * A CustomTask is a user-defined task that can be dynamically added to the app.
 *
 * The user journey for a custom task begins on the home screen, which is organized into categories.
 * These categories correspond to the tabs in the main navigation. Within each category, a list of
 * tasks is displayed. A task represents a specific functionality or use case, and it includes
 * metadata like a label, description, and icon, which are all defined in the `task` property of
 * your `CustomTask` implementation.
 *
 * When a user selects a task on the home screen, they are taken to the task's detail screen. This
 * screen displays the task's description and presents a list of associated models. A single task
 * can have multiple models, allowing the user to choose between different implementations or
 * versions (e.g., different LLM models for a "Chat" task). The user can then select and run a
 * specific model for the task.
 *
 * To create your own custom task, follow these steps:
 * 1. Create a class that implements this `CustomTask` interface.
 * 2. Define the metadata for your task in the `task` property, including its label, description,
 *    and associated models.
 * 3. Implement the `initializeModelFn` and `cleanUpModelFn` functions to handle the setup and
 *    teardown logic for your task's models.
 * 4. Implement the `MainScreen` composable to define the UI for your task's model detail screen.
 *    This is where the user will interact with the model within your task. It's important to note
 *    that this UI will be placed inside a pre-configured `Scaffold` that already handles the app
 *    bar, *which includes the model name, a model selector, and a configuration button. Your focus
 *    here should be on building the main content area of the screen.
 * 5. Create a Hilt module and use `@Provides` and `@IntoSet` to bind your custom task
 *    implementation into a set of `CustomTask`s. This makes your task automatically discoverable by
 *    the app's home screen.
 *
 * For a concrete example of how to implement these steps, see the
 * [com.google.ai.edge.gallery.customtasks.examplecustomtask.ExampleCustomTask] class. This example
 * implements a "Model Viewer" task that displays the text content of a model file for demonstration
 * purpose. See comments there for more details.
 *
 */
interface CustomTask {
  /**
   * The metadata for your task and the models within the task.
   *
   * See comments of [Task] for details.
   */
  val task: Task

  /**
   * Called to initialize and prepare a model for use.
   *
   * This function will be called from a coroutine with Dispatchers.Default dispatcher.
   *
   * @param context The application context.
   * @param coroutineScope The coroutine scope for asynchronous operations.
   * @param model The `Model` object containing information about the model to be initialized.
   * @param onDone A callback function to be invoked when initialization is complete. Pass an empty
   *   string on success, or an error message on failure.
   */
  fun initializeModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    onDone: (error: String) -> Unit,
  )

  /**
   * Called to clean up resources associated with a model.
   *
   * @param context The application context.
   * @param coroutineScope The coroutine scope for asynchronous operations.
   * @param model The `Model` object to be cleaned up.
   * @param onDone A callback function to be invoked when cleanup is complete.
   */
  fun cleanUpModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    onDone: () -> Unit,
  )

  /**
   * The main Composable UI for your custom task's detail screen.
   *
   * @param data The data sent from the app. It will typically be a [CustomTaskData].
   */
  @Composable fun MainScreen(data: Any)
}
