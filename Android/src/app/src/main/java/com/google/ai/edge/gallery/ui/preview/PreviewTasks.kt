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

package com.google.ai.edge.gallery.ui.preview

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBox
import androidx.compose.material.icons.rounded.AutoAwesome
import com.google.ai.edge.gallery.data.BooleanSwitchConfig
import com.google.ai.edge.gallery.data.Config
import com.google.ai.edge.gallery.data.ConfigKey
import com.google.ai.edge.gallery.data.LabelConfig
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.NumberSliderConfig
import com.google.ai.edge.gallery.data.SegmentedButtonConfig
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.data.TaskType
import com.google.ai.edge.gallery.data.ValueType

val TEST_CONFIGS1: List<Config> =
  listOf(
    LabelConfig(key = ConfigKey.NAME, defaultValue = "Test name"),
    NumberSliderConfig(
      key = ConfigKey.MAX_RESULT_COUNT,
      sliderMin = 1f,
      sliderMax = 5f,
      defaultValue = 3f,
      valueType = ValueType.INT,
    ),
    BooleanSwitchConfig(key = ConfigKey.USE_GPU, defaultValue = false),
    SegmentedButtonConfig(
      key = ConfigKey.THEME,
      defaultValue = "Auto",
      options = listOf("Auto", "Light", "Dark"),
    ),
  )

val MODEL_TEST1: Model =
  Model(
    name = "deterministic3",
    downloadFileName = "deterministric3.json",
    url = "https://storage.googleapis.com/tfweb/model-graph-vis-v2-test-models/deterministic3.json",
    sizeInBytes = 40146048L,
    configs = TEST_CONFIGS1,
  )

val MODEL_TEST2: Model =
  Model(
    name = "isnet",
    downloadFileName = "isnet.tflite",
    url =
      "https://storage.googleapis.com/tfweb/model-graph-vis-v2-test-models/isnet-general-use-int8.tflite",
    sizeInBytes = 44366296L,
    configs = TEST_CONFIGS1,
  )

val MODEL_TEST3: Model =
  Model(
    name = "yolo",
    downloadFileName = "yolo.json",
    url = "https://storage.googleapis.com/tfweb/model-graph-vis-v2-test-models/yolo.json",
    sizeInBytes = 40641364L,
  )

val MODEL_TEST4: Model =
  Model(
    name = "mobilenet v3",
    downloadFileName = "mobilenet_v3_large.pt2",
    url =
      "https://storage.googleapis.com/tfweb/model-graph-vis-v2-test-models/mobilenet_v3_large.pt2",
    sizeInBytes = 277135998L,
  )

val TASK_TEST1 =
  Task(
    type = TaskType.TEST_TASK_1,
    icon = Icons.Rounded.AutoAwesome,
    models = mutableListOf(MODEL_TEST1, MODEL_TEST2),
    description = "This is a test task (1)",
  )

val TASK_TEST2 =
  Task(
    type = TaskType.TEST_TASK_2,
    icon = Icons.Rounded.AccountBox,
    models = mutableListOf(MODEL_TEST3, MODEL_TEST4),
    description = "This is a test task (2)",
  )

val ALL_PREVIEW_TASKS: List<Task> = listOf(TASK_TEST1, TASK_TEST2)
