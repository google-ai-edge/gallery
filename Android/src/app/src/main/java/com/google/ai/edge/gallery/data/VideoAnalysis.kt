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

import android.graphics.Bitmap
import kotlinx.serialization.Serializable

@Serializable
data class FrameData(
  val timestamp: Long,
  val frameNumber: Int,
  val imagePath: String? = null,
)

@Serializable
data class DetectedObject(
  val name: String,
  val confidence: Float,
  val description: String,
  val position: ObjectPosition? = null,
)

@Serializable
data class ObjectPosition(
  val x: Float,
  val y: Float,
  val width: Float,
  val height: Float,
)

@Serializable
data class VideoAnalysisResult(
  val sessionId: String,
  val timestamp: String,
  val totalFrames: Int,
  val detectedObjects: List<DetectedObject>,
  val summary: String,
  val confidenceScore: Float,
)

data class CapturedFrame(
  val bitmap: Bitmap,
  val frameData: FrameData,
)

enum class CaptureState {
  IDLE,
  CAPTURING,
  ANALYZING,
  COMPLETED,
  ERROR,
}

data class VideoCaptureProgress(
  val state: CaptureState,
  val currentFrame: Int,
  val totalFrames: Int,
  val message: String = "",
)