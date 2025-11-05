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

package com.neuralforge.mobile.data

import androidx.compose.ui.unit.dp

// Keys used to send/receive data to Work.
const val KEY_MODEL_URL = "KEY_MODEL_URL"
const val KEY_MODEL_NAME = "KEY_MODEL_NAME"
const val KEY_MODEL_COMMIT_HASH = "KEY_MODEL_COMMIT_HASH"
const val KEY_MODEL_DOWNLOAD_MODEL_DIR = "KEY_MODEL_DOWNLOAD_MODEL_DIR"
const val KEY_MODEL_DOWNLOAD_FILE_NAME = "KEY_MODEL_DOWNLOAD_FILE_NAME"
const val KEY_MODEL_TOTAL_BYTES = "KEY_MODEL_TOTAL_BYTES"
const val KEY_MODEL_DOWNLOAD_RECEIVED_BYTES = "KEY_MODEL_DOWNLOAD_RECEIVED_BYTES"
const val KEY_MODEL_DOWNLOAD_RATE = "KEY_MODEL_DOWNLOAD_RATE"
const val KEY_MODEL_DOWNLOAD_REMAINING_MS = "KEY_MODEL_DOWNLOAD_REMAINING_SECONDS"
const val KEY_MODEL_DOWNLOAD_ERROR_MESSAGE = "KEY_MODEL_DOWNLOAD_ERROR_MESSAGE"
const val KEY_MODEL_DOWNLOAD_ACCESS_TOKEN = "KEY_MODEL_DOWNLOAD_ACCESS_TOKEN"
const val KEY_MODEL_EXTRA_DATA_URLS = "KEY_MODEL_EXTRA_DATA_URLS"
const val KEY_MODEL_EXTRA_DATA_DOWNLOAD_FILE_NAMES = "KEY_MODEL_EXTRA_DATA_DOWNLOAD_FILE_NAMES"
const val KEY_MODEL_IS_ZIP = "KEY_MODEL_IS_ZIP"
const val KEY_MODEL_UNZIPPED_DIR = "KEY_MODEL_UNZIPPED_DIR"
const val KEY_MODEL_START_UNZIPPING = "KEY_MODEL_START_UNZIPPING"

// Default values for LLM models.
const val DEFAULT_MAX_TOKEN = 1024
const val DEFAULT_TOPK = 40
const val DEFAULT_TOPP = 0.9f
const val DEFAULT_TEMPERATURE = 1.0f
val DEFAULT_ACCELERATORS = listOf(Accelerator.GPU)

// Max number of images allowed in a "ask image" session.
const val MAX_IMAGE_COUNT = 10

// Max number of audio clip in an "ask audio" session.
const val MAX_AUDIO_CLIP_COUNT = 1

// Max audio clip duration in seconds.
const val MAX_AUDIO_CLIP_DURATION_SEC = 30

// Audio-recording related consts.
const val SAMPLE_RATE = 16000

// The size the icon shown under each of the model names in the model list screen.
val MODEL_INFO_ICON_SIZE = 18.dp

// The extension of the tmp download files.
const val TMP_FILE_EXT = "gallerytmp"
