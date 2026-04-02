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

package com.google.ai.edge.gallery.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.squareup.moshi.JsonClass
import kotlinx.coroutines.CompletableDeferred

interface LatencyProvider {
  val latencyMs: Float
}

data class Classification(val label: String, val score: Float, val color: Color)

data class JsonObjAndTextContent<T>(val jsonObj: T, val textContent: String)

class AudioClip(val audioData: ByteArray, val sampleRate: Int)

open class AgentAction(val name: AgentActionName)

class CallJsAgentAction(
  val url: String,
  val data: String,
  val secret: String = "",
  val result: CompletableDeferred<String> = CompletableDeferred(),
) : AgentAction(name = AgentActionName.CALL_JS_SKILL) {}

class AskInfoAgentAction(
  val dialogTitle: String,
  val fieldLabel: String,
  val result: CompletableDeferred<String> = CompletableDeferred(),
) : AgentAction(name = AgentActionName.ASK_INFO)

class SkillProgressAgentAction(
  val label: String,
  val inProgress: Boolean,
  val addItemTitle: String = "",
  val addItemDescription: String = "",
  val customData: Any? = null,
) : AgentAction(name = AgentActionName.SKILL_PROGRESS)

enum class AgentActionName() {
  CALL_JS_SKILL,
  SKILL_PROGRESS,
  ASK_INFO,
}

data class SkillTryOutChip(
  val icon: ImageVector,
  val label: String,
  val prompt: String,
  val skillName: String,
)

data class SkillInfo(
  val skillMd: String,
  val skillUrl: String? = null,
  val tryoutChip: SkillTryOutChip? = null,
)

data class SkillsIndex(val skills: List<SkillInfo>)

@JsonClass(generateAdapter = true)
data class CallJsSkillResult(
  val result: String?,
  val error: String?,
  val image: CallJsSkillResultImage?,
  val webview: CallJsSkillResultWebview?,
)

@JsonClass(generateAdapter = true) data class CallJsSkillResultImage(val base64: String?)

@JsonClass(generateAdapter = true)
data class CallJsSkillResultWebview(
  val url: String?,
  val iframe: Boolean?,
  // width/height.
  //
  // In the app the webview always takes the full width of the screen. This value is used to
  // calculate the height of the webview. Default is 4:3.
  val aspectRatio: Float?,
)
