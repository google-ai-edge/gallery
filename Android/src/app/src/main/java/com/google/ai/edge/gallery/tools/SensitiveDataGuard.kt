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

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "AGSensitiveDataGuard"

/**
 * Per-conversation user consent for tools that touch sensitive, permission-gated user data.
 *
 * An Android runtime permission is granted once and then covers every later read, so on its own it
 * cannot stop a prompt-injected model (e.g. one following a malicious third-party skill) from
 * silently reading the user's data. This guard asks the user before each read unless they allowed
 * that kind of data for the rest of the conversation, and remembers that sensitive data has entered
 * the conversation so that later steps can ask again before the data leaves the app.
 *
 * Each [ToolExecutionContext] owns one guard, so consent never outlives the conversation. Fails
 * closed: when there is no channel to ask the user on, access is denied. Thread-safe.
 *
 * @param hasSensitiveDataInContext Whether the conversation starts with history that may already
 *   hold sensitive data, e.g. a restored conversation whose origin is unknown.
 */
class SensitiveDataGuard(hasSensitiveDataInContext: Boolean = false) {
  private val sensitiveDataInContext = AtomicBoolean(hasSensitiveDataInContext)
  private val typesAllowedForConversation: MutableSet<SensitiveDataType> =
    ConcurrentHashMap.newKeySet()

  /**
   * Serializes consent decisions, so that concurrent requests show one prompt at a time and a
   * waiting request sees an "allow for this conversation" decision made by the one before it.
   */
  private val decisionMutex = Mutex()

  /** Whether sensitive user data may be present in the conversation. */
  val hasSensitiveDataInContext: Boolean
    get() = sensitiveDataInContext.get()

  /**
   * Returns whether a tool may read [type] data, asking the user through [actionChannel] unless
   * they already allowed [type] for this conversation. [details] are the model-supplied parameters
   * of the read, shown to the user.
   *
   * An allowed read marks the conversation as holding sensitive data, before the read happens, so
   * that a failed or partial read still errs on the side of asking again later.
   */
  suspend fun requestRead(
    type: SensitiveDataType,
    details: String,
    actionChannel: SendChannel<ToolAction>?,
  ): Boolean = decisionMutex.withLock {
    val allowed =
      type in typesAllowedForConversation ||
        askUser(SensitiveDataRequest.Read(type = type, details = details), actionChannel) {
          typesAllowedForConversation.add(type)
        }
    if (allowed) {
      sensitiveDataInContext.set(true)
    }
    allowed
  }

  /**
   * Asks the user to decide on [request] and returns whether it is allowed, calling
   * [allowForConversation] if the user allowed it for the rest of the conversation.
   */
  private suspend fun askUser(
    request: SensitiveDataRequest,
    actionChannel: SendChannel<ToolAction>?,
    allowForConversation: () -> Unit,
  ): Boolean =
    when (promptUser(request, actionChannel)) {
      SensitiveDataDecision.DENY -> false
      SensitiveDataDecision.ALLOW_ONCE -> true
      SensitiveDataDecision.ALLOW_FOR_CONVERSATION -> {
        allowForConversation()
        true
      }
    }

  private suspend fun promptUser(
    request: SensitiveDataRequest,
    actionChannel: SendChannel<ToolAction>?,
  ): SensitiveDataDecision {
    if (actionChannel == null) {
      Log.w(TAG, "No action channel to ask the user on. Denying ${request::class.simpleName}.")
      return SensitiveDataDecision.DENY
    }
    val action = AskSensitiveDataPermissionAction(request = request)
    actionChannel.send(action)
    return action.result.await()
  }
}
