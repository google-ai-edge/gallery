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

package com.google.ai.edge.gallery.ui.llmchat

import android.content.Context
import android.util.Log
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.MessageCallback
import kotlinx.coroutines.flow.emitAll

private const val TAG = "LlmSafeExecutionHelper"

suspend fun LlmModelInstance.sendMessageSafely(
  model: Model,
  contents: Contents,
  context: Context,
  task: Task,
  modelManager: ModelManagerViewModel,
): String {
  if (!this.conversation.isAlive) {
    Log.w(TAG, "Conversation died in background. Resurrecting...")
    val success = modelManager.reinitializeModelSafely(context, task, model)
    if (!success) {
      throw IllegalStateException("Failed to recover conversation context.")
    }
  }
  return this.conversation.sendMessage(contents).toString()
}

suspend fun LlmModelInstance.sendMessageAsyncSafely(
  model: Model,
  contents: Contents,
  context: Context,
  task: Task,
  modelManager: ModelManagerViewModel,
  messageCallback: MessageCallback,
  extraContext: Map<String, String>? = null,
) {
  if (!this.conversation.isAlive) {
    Log.w(TAG, "Conversation died in background. Resurrecting...")
    val success = modelManager.reinitializeModelSafely(context, task, model)
    if (!success) {
      messageCallback.onError(IllegalStateException("Failed to recover conversation context."))
      return
    }
  }

  try {
    if (extraContext != null) {
      this.conversation.sendMessageAsync(contents, messageCallback, extraContext)
    } else {
      this.conversation.sendMessageAsync(contents, messageCallback, emptyMap())
    }
  } catch (e: Exception) {
    messageCallback.onError(e)
  }
}

fun LlmModelInstance.sendMessageAsyncFlowSafely(
  model: Model,
  contents: Contents,
  context: Context,
  task: Task,
  modelManager: ModelManagerViewModel,
): kotlinx.coroutines.flow.Flow<com.google.ai.edge.litertlm.Message> =
  kotlinx.coroutines.flow.flow {
    if (!this@sendMessageAsyncFlowSafely.conversation.isAlive) {
      Log.w(TAG, "Conversation died in background. Resurrecting...")
      val success = modelManager.reinitializeModelSafely(context, task, model)
      if (!success) {
        throw IllegalStateException("Failed to recover conversation context.")
      }
    }
    val flow = this@sendMessageAsyncFlowSafely.conversation.sendMessageAsync(contents)
    emitAll(flow)
  }
