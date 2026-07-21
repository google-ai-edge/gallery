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

package com.google.ai.edge.gallery.tools

import com.squareup.moshi.JsonClass
import kotlinx.coroutines.CompletableDeferred

/** Enumerates the types of action events produced by running tool definitions. */
enum class ToolActionName() {
  CALL_JS_SKILL,
  SKILL_PROGRESS,
  ASK_INFO,
  REQUEST_PERMISSION,
  ASK_MCP_TOOL_CALL_PERMISSION,
}

/**
 * Base class for dynamic side-effect, communication, and authorization actions emitted by a running
 * tool into [ToolExecutionContext.actionChannel].
 *
 * @property name Identifies the category of this tool action.
 */
open class ToolAction(val name: ToolActionName)

/**
 * Emitted when a tool executes an external JavaScript skill script inside an embedded JS/WebView
 * runtime.
 *
 * @property url The URL or local asset path of the target JavaScript skill script.
 * @property data A JSON-formatted input string passed into the script execution.
 * @property secret An optional secret (e.g., API token) injected into the script if required.
 * @property result Completed with the raw JSON result string produced by the JS execution.
 */
class CallJsToolAction(
  val url: String,
  val data: String,
  val secret: String = "",
  val result: CompletableDeferred<String> = CompletableDeferred(),
) : ToolAction(name = ToolActionName.CALL_JS_SKILL) {}

/**
 * Emitted to display an interactive dialog prompting the user for required text information (such
 * as an API token or parameter).
 *
 * @property dialogTitle The header text displayed on the prompt dialog.
 * @property fieldLabel The descriptive label telling the user what information must be entered.
 * @property result Completed with the text value entered by the user.
 */
class AskInfoToolAction(
  val dialogTitle: String,
  val fieldLabel: String,
  val result: CompletableDeferred<String> = CompletableDeferred(),
) : ToolAction(name = ToolActionName.ASK_INFO)

/**
 * Emitted to publish real-time progress events or execution status items to the agent chat UI.
 *
 * @property label The concise status summary shown in the progress panel (e.g., "Calling JS
 *   script...").
 * @property inProgress Whether this step is actively running or completed.
 * @property addItemTitle Optional title for an expanded detailed item added to the progress log.
 * @property addItemDescription Optional multi-line details or parameters shown under
 *   [addItemTitle].
 * @property customData Optional tool or skill metadata associated with this progress item.
 */
class SkillProgressToolAction(
  val label: String,
  val inProgress: Boolean,
  val addItemTitle: String = "",
  val addItemDescription: String = "",
  val customData: Any? = null,
) : ToolAction(name = ToolActionName.SKILL_PROGRESS)

/**
 * Emitted to request an Android system permission (e.g., calendar or camera access) required by a
 * tool or intent.
 *
 * @property permission The Android permission string (e.g., `Manifest.permission.READ_CALENDAR`).
 * @property result Completed with true if the user granted the permission, false if denied.
 */
class RequestPermissionToolAction(
  val permission: String,
  val result: CompletableDeferred<Boolean> = CompletableDeferred(),
) : ToolAction(name = ToolActionName.REQUEST_PERMISSION)

/** Represents the result of a permission request in [AskMcpToolCallPermissionAction]. */
enum class PermissionResult {
  DENY,
  ALLOW_ONCE,
  ALWAYS_ALLOW,
}

/**
 * Emitted to request user permission for a specific Model Context Protocol (MCP) tool invocation.
 *
 * @property toolName The name of the MCP tool about to be executed.
 * @property argument The JSON string of arguments to be passed to the MCP tool.
 * @property result Completed with the user's decision ([PermissionResult]).
 */
class AskMcpToolCallPermissionAction(
  val toolName: String,
  val argument: String,
  val result: CompletableDeferred<PermissionResult> = CompletableDeferred(),
) : ToolAction(name = ToolActionName.ASK_MCP_TOOL_CALL_PERMISSION)

/**
 * Parsed JSON data returned from a JavaScript skill execution (`[CallJsToolAction]`).
 *
 * @property result The textual response or return value produced by the script.
 * @property error An error message if the JavaScript execution threw an exception or failed.
 * @property image An optional image side-effect returned by the script.
 * @property webview An optional embedded web view side-effect returned by the script.
 */
@JsonClass(generateAdapter = true)
data class CallJsSkillResult(
  val result: String?,
  val error: String?,
  val image: CallJsSkillResultImage?,
  val webview: CallJsSkillResultWebview?,
)

/** Represents an image side-effect returned by a JavaScript skill, encoded as a [base64] string. */
@JsonClass(generateAdapter = true) data class CallJsSkillResultImage(val base64: String?)

/**
 * Represents an embedded web view side-effect returned by a JavaScript skill to be rendered inside
 * the chat flow.
 */
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
