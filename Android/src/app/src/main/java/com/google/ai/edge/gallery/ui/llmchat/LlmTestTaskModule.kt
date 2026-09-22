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
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.agent.AgentRuntimeExecutor
import com.google.ai.edge.gallery.agent.AiChatExecutor
import com.google.ai.edge.gallery.customtasks.common.CustomTask
import com.google.ai.edge.gallery.data.BuiltInTaskId
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Inject

/**
 * Dedicated "Test it" task for user-imported OSS models.
 *
 * Extends [BaseLlmChatTask] so that model initialization, cleanup, and the full chat experience
 * (including TTS/ASR and context management) are shared with [LlmChatTask] without
 * concrete-to-concrete inheritance.
 */
class LlmTestTask
@Inject
constructor(@ApplicationContext context: Context, @AiChatExecutor executor: AgentRuntimeExecutor) :
  BaseLlmChatTask(
    context = context,
    executor = executor,
    taskId = BuiltInTaskId.LLM_TEST,
    labelRes = R.string.test_chat,
    descriptionRes = R.string.task_desc_test,
  )

@Module
@InstallIn(SingletonComponent::class)
internal object LlmTestTaskModule {
  @Provides
  @IntoSet
  fun provideTask(
    @ApplicationContext context: Context,
    @AiChatExecutor executor: AgentRuntimeExecutor,
  ): CustomTask {
    return LlmTestTask(context, executor)
  }
}
