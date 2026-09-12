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

/**
 * Platform and runtime backend configuration for running a [Model].
 *
 * @property runtimeType The type of local runtime environment to use for running the model.
 * @property aicoreReleaseStage The release stage of the AICore model.
 * @property aicorePreference The preference of the AICore model.
 */
data class BackendSpec(
  val runtimeType: RuntimeType = RuntimeType.UNKNOWN,
  val aicoreReleaseStage: AICoreModelReleaseStage? = null,
  val aicorePreference: AICoreModelPreference? = null,
) {
  /** Indicates whether the runtime type is AICore. */
  val isAiCore: Boolean
    get() = runtimeType == RuntimeType.AICORE

  /** Indicates whether the runtime type is LiteRT-LM. */
  val isLiteRtLm: Boolean
    get() = runtimeType == RuntimeType.LITERT_LM
}
