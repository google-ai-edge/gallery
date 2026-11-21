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

package com.google.ai.edge.gallery.customtasks.agentchat

import com.google.ai.edge.gallery.agent.AgentChatExecutor
import com.google.ai.edge.gallery.agent.AgentRuntimeExecutor
import com.google.ai.edge.gallery.agent.PromptExpander
import com.google.ai.edge.gallery.agent.sessions.LlmSessionManager
import com.google.ai.edge.gallery.data.DataStoreRepository
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.SystemPromptRepository
import com.google.ai.edge.gallery.di.IoDispatcher
import com.google.ai.edge.gallery.skills.ConversationSkillsProvider
import com.google.ai.edge.gallery.skills.NoOpSkillsProvider
import com.google.ai.edge.gallery.skills.SkillsProvider
import com.google.ai.edge.gallery.skills.SlashSkillMatcher
import com.google.ai.edge.gallery.skills.formatAvailableSkills
import com.google.ai.edge.gallery.tools.ToolAction
import com.google.ai.edge.gallery.ui.llmchat.LlmChatViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.SendChannel

@HiltViewModel
class AgentChatViewModel : LlmChatViewModel {

  private var boundConversationSkillsProvider: ConversationSkillsProvider? = null
  private var agentBaseSystemPromptTemplate: String? = null
  private var agentToolsPrompt: String = ""
  private var agentActionChannel: SendChannel<ToolAction>? = null

  @Inject
  constructor(
    systemPromptRepository: SystemPromptRepository,
    @AgentChatExecutor runtimeExecutor: AgentRuntimeExecutor,
    llmSessionManager: LlmSessionManager,
    dataStoreRepository: DataStoreRepository?,
    conversationSkillsProvider: ConversationSkillsProvider,
    @IoDispatcher ioDispatcher: CoroutineDispatcher,
  ) :
    super(systemPromptRepository, runtimeExecutor,
    llmSessionManager, dataStoreRepository, ioDispatcher) {
    boundConversationSkillsProvider = conversationSkillsProvider
  }

  constructor(
    systemPromptRepository: SystemPromptRepository,
    runtimeExecutor: AgentRuntimeExecutor,
    llmSessionManager: LlmSessionManager,
    dataStoreRepository: DataStoreRepository? = null,
  ) :
  super(systemPromptRepository, runtimeExecutor,
  llmSessionManager, dataStoreRepository, Dispatchers.IO)

  /** Returns the active [ConversationSkillsProvider], wrapping [fallbackProvider] if unbound. */
  fun bindSkillsProviderIfNeeded(fallbackProvider: SkillsProvider): ConversationSkillsProvider {
    boundConversationSkillsProvider?.let {
      return it
    }
    return ConversationSkillsProvider(baseProvider = fallbackProvider).also {
      boundConversationSkillsProvider = it
    }
  }

  /** Updates Agent Skills session configuration used when rebuilding the runtime prompt. */
  fun configureAgentSession(
    baseSystemPrompt: String,
    toolsPrompt: String = "",
    actionChannel: SendChannel<ToolAction>? = null,
  ) {
    agentBaseSystemPromptTemplate = baseSystemPrompt
    agentToolsPrompt = toolsPrompt
    agentActionChannel = actionChannel
  }

  /** Clears conversation-scoped slash skill activations and restores global defaults. */
  fun resetConversationSkills() {
    clearConversationState()
  }

  override fun clearConversationState(model: Model?) {
    boundConversationSkillsProvider?.clearActivatedSkills()
  }

  override fun defaultActionChannel(): SendChannel<ToolAction>? = agentActionChannel

  override fun defaultEnableConversationConstrainedDecoding(): Boolean = true

  override suspend fun normalizeInputForRequest(input: String): String {
    if (input.isEmpty()) {
      return input
    }
    val allSkills = boundConversationSkillsProvider?.getAllSkills().orEmpty()
    return SlashSkillMatcher.normalizeSkillSlashes(input = input, skills = allSkills)
  }

  override suspend fun prepareRuntimeForRequest(model: Model, input: String): Boolean {
    if (input.isEmpty()) {
      return false
    }
    val skillsProvider = boundConversationSkillsProvider ?: return false
    val allSkills = skillsProvider.getAllSkills()
    if (allSkills.isEmpty()) {
      return false
    }
    val selectedSkills = SlashSkillMatcher.selectedSkills(input = input, skills = allSkills)
    val activeNormalizedNames =
      skillsProvider.getAvailableSkills().mapTo(hashSetOf()) {
        SlashSkillMatcher.normalizedName(it.name)
      }
    val disabledSkillNames =
      selectedSkills
        .map { it.name }
        .filter { SlashSkillMatcher.normalizedName(it) !in activeNormalizedNames }
    return skillsProvider.activateSkills(disabledSkillNames)
  }

  override suspend fun resolveSystemInstructionForReset(
    model: Model,
    baseSystemInstruction: String?,
  ): String? {
    val rawTemplate =
      agentBaseSystemPromptTemplate
        ?: baseSystemInstruction?.takeIf { it.isNotEmpty() }
        ?: return null
    val effectiveTemplate =
      getEffectiveBaseSystemPrompt(
        currentPrompt = rawTemplate,
        hasMcpTools = agentToolsPrompt.isNotEmpty(),
      )
    val skillsProvider =
      boundConversationSkillsProvider ?: ConversationSkillsProvider(NoOpSkillsProvider())
    val availableSkills = skillsProvider.getAvailableSkills()
    return PromptExpander()
      .formatSystemInstructions(
        template = effectiveTemplate,
        substitutions =
          mapOf(
            "___SKILLS___" to formatAvailableSkills(availableSkills),
            "___TOOLS___" to agentToolsPrompt,
          ),
      )
  }

  override fun onCleared() {
    clearConversationState()
  }
}
