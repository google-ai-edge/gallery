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

import com.google.gson.annotations.SerializedName

/**
 * Represents metadata about an additional or supplementary data file that must be downloaded
 * alongside the main model.
 */
data class ModelDataFile(
  /** The user-facing name of the data file. */
  val name: String,
  /** The URL to download the data file from. */
  val url: String,
  /** The name of the downloaded data file. */
  val downloadFileName: String,
  /** The size of the data file in bytes. */
  val sizeInBytes: Long,
  /** The task types this data file is targeted for. */
  val targetTaskTypes: List<String> = emptyList(),
)

data class ModelFile(
  @SerializedName("fileName") val fileName: String,
  @SerializedName("commitHash") val commitHash: String,
)
