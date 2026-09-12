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

package com.google.ai.edge.gallery.customtasks.livevideo

enum class VideoMode(
  val label: String,
  val prompt: String,
  val hasAudioIO: Boolean = false,
  val maxFrames: Int = 1,
  val useGaze: Boolean = false,
  val autoIntervalMs: Long = 3000L,
  val imageMaxPx: Int = SL70_PX,
  val temperature: Float = 0.8f,
) {
  CONVERSATION(
    label = "Visual Chat",
    prompt = "You see a live camera. Answer the user's question about what you see. Be brief.",
    hasAudioIO = true,
    maxFrames = 8,
    useGaze = true,
    imageMaxPx = SL70_PX,
    temperature = 0.8f,
  ),
  CAPTION(
    label = "Captioning",
    prompt = "One sentence: what is in this image?",
    maxFrames = 8,
    useGaze = true,
    autoIntervalMs = 1500L,
    imageMaxPx = SL70_PX,
    temperature = 0.3f,
  ),
  FINGERS(
    label = "Counting",
    prompt = "Count extended fingers. Reply ONLY with the number.",
    maxFrames = 8,
    useGaze = true,
    autoIntervalMs = 1000L,
    imageMaxPx = SL70_PX,
    temperature = 0.0f,
  ),
  SMART_CAMERA(
    label = "Smart Camera",
    prompt = "",
    maxFrames = 4,
    useGaze = true,
    autoIntervalMs = 3000L,
    imageMaxPx = SL70_PX,
    temperature = 0.2f,
  ),
}

const val SL70_PX = 384
const val SL280_PX = 768

data class LiveVideoModelInstance(val gemmaGaze: GemmaGazeInterpreter)
