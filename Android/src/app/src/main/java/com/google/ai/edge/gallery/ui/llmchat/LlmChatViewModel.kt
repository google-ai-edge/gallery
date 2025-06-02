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

package com.google.ai.edge.gallery.ui.llmchat

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.data.ConfigKey
import com.google.ai.edge.gallery.data.Conversation
import com.google.ai.edge.gallery.data.DataStoreRepository
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.Persona
import com.google.ai.edge.gallery.data.TASK_LLM_CHAT
// import com.google.ai.edge.gallery.data.TASK_LLM_ASK_IMAGE // Removed
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.data.UserProfile
import com.google.ai.edge.gallery.data.ChatMessage // Assuming ChatMessage is in data package from PersonalAppModels.kt
import com.google.ai.edge.gallery.data.ChatMessageRole
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageBenchmarkLlmResult
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageLoading
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageText
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageType
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageWarning
import com.google.ai.edge.gallery.ui.common.chat.ChatSide
import com.google.ai.edge.gallery.ui.common.chat.ChatViewModel
import com.google.ai.edge.gallery.ui.common.chat.Stat
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID

private const val TAG = "AGLlmChatViewModel"
private val STATS = listOf(
  Stat(id = "time_to_first_token", label = "1st token", unit = "sec"),
  Stat(id = "prefill_speed", label = "Prefill speed", unit = "tokens/s"),
  Stat(id = "decode_speed", label = "Decode speed", unit = "tokens/s"),
  Stat(id = "latency", label = "Latency", unit = "sec")
)

open class LlmChatViewModel(
    private val dataStoreRepository: DataStoreRepository, // Add this
    curTask: Task = TASK_LLM_CHAT // Keep if still relevant, or simplify if chat is the only focus
) : ChatViewModel(task = curTask) { // ChatViewModel base class might need review

    private val _currentConversation = MutableStateFlow<Conversation?>(null)
    val currentConversation: StateFlow<Conversation?> = _currentConversation.asStateFlow()

    val userProfile: StateFlow<UserProfile?> = flow {
        emit(dataStoreRepository.readUserProfile())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // Get active persona ID from repo, then fetch the full Persona object
    val activePersona: StateFlow<Persona?> = dataStoreRepository.readActivePersonaId().flatMapLatest { activeId ->
        if (activeId == null) {
            flowOf(null)
        } else {
            flow {
                val personas = dataStoreRepository.readPersonas()
                emit(personas.find { it.id == activeId })
            }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // This replaces messagesByModel for the new conversation-centric approach
    private val _uiMessages = MutableStateFlow<List<com.google.ai.edge.gallery.ui.common.chat.ChatMessage>>(emptyList())
    val uiMessages: StateFlow<List<com.google.ai.edge.gallery.ui.common.chat.ChatMessage>> = _uiMessages.asStateFlow()

    // TODO: Review how ChatViewModel's messagesByModel and related methods
    // (addMessage, getLastMessage, removeLastMessage, clearAllMessages) are used.
    // They might need to be overridden or adapted to work with _currentConversation.messages
    // and update _uiMessages. For now, new methods will manage _currentConversation.

    fun startNewConversation(customSystemPrompt: String?, selectedPersonaId: String?, title: String? = null, selectedModel: Model) {
        viewModelScope.launch {
            val newConversationId = UUID.randomUUID().toString()
            val newConversation = Conversation(
                id = newConversationId,
                title = title,
                creationTimestamp = System.currentTimeMillis(),
                lastModifiedTimestamp = System.currentTimeMillis(),
                initialSystemPrompt = customSystemPrompt,
                activePersonaId = selectedPersonaId,
                modelIdUsed = selectedModel.name, // Store the model name or a unique ID
                messages = mutableListOf() // Start with empty messages
            )
            dataStoreRepository.addConversation(newConversation)
            _currentConversation.value = newConversation
            _uiMessages.value = emptyList() // Clear UI messages for the new chat

            // Reset LLM session for the selected model
            LlmChatModelHelper.resetSession(selectedModel) // Ensure model instance is ready

            // Prime with system prompt immediately if starting a new conversation
            val systemPromptParts = mutableListOf<String>()
            customSystemPrompt?.let { systemPromptParts.add(it) }
            activePersona.value?.prompt?.let { systemPromptParts.add(it) }
            userProfile.value?.summary?.let { if(it.isNotBlank()) systemPromptParts.add("User Profile Summary: $it") }
            // Add other profile details as needed, e.g., skills

            if (systemPromptParts.isNotEmpty()) {
                val fullSystemPrompt = systemPromptParts.joinToString("\n\n")
                // Ensure model instance is available and ready before priming
                if (selectedModel.instance == null) {
                   Log.e(TAG, "Model instance is null before priming. Initialize model first.")
                   // Potentially trigger model initialization via ModelManagerViewModel if not already done.
                   // For now, we assume the model selected for chat is already initialized by ModelManager.
                   return@launch
                }
                LlmChatModelHelper.primeSessionWithSystemPrompt(selectedModel, fullSystemPrompt)
            }
        }
    }

    fun loadConversation(conversationId: String, selectedModel: Model) {
        viewModelScope.launch {
            val conversation = dataStoreRepository.getConversationById(conversationId)
            _currentConversation.value = conversation
            if (conversation != null) {
                // Convert stored ChatMessage to UI ChatMessage
                _uiMessages.value = conversation.messages.map { convertToUiChatMessage(it, selectedModel) }

                // Reset session and re-prime with history
                LlmChatModelHelper.resetSession(selectedModel)

                val systemPromptParts = mutableListOf<String>()
                conversation.initialSystemPrompt?.let { systemPromptParts.add(it) }
                // Need to fetch persona for this conversation
                val personas = dataStoreRepository.readPersonas()
                val personaForLoadedConv = personas.find { it.id == conversation.activePersonaId }
                personaForLoadedConv?.prompt?.let { systemPromptParts.add(it) }
                userProfile.value?.summary?.let { if(it.isNotBlank()) systemPromptParts.add("User Profile Summary: $it") }
                // Add other profile details

                if (systemPromptParts.isNotEmpty()) {
                    LlmChatModelHelper.primeSessionWithSystemPrompt(selectedModel, systemPromptParts.joinToString("\n\n"))
                }
                // Replay message history into the session
                conversation.messages.forEach { msg ->
                    if (msg.role == ChatMessageRole.USER) {
                        (selectedModel.instance as? LlmModelInstance)?.session?.addQueryChunk(msg.content)
                    } else if (msg.role == ChatMessageRole.ASSISTANT) {
                        // If LLM Inference API supports adding assistant messages to context, do it here.
                        // For now, assume addQueryChunk is for user input primarily to prompt next response.
                       (selectedModel.instance as? LlmModelInstance)?.session?.addQueryChunk(msg.content) // Or format appropriately
                    }
                }
            } else {
                _uiMessages.value = emptyList()
            }
        }
    }

    // Helper to convert your data model ChatMessage to the UI model ChatMessage
    private fun convertToUiChatMessage(appMessage: com.google.ai.edge.gallery.data.ChatMessage, model: Model): com.google.ai.edge.gallery.ui.common.chat.ChatMessage {
        val side = if (appMessage.role == ChatMessageRole.USER) ChatSide.USER else ChatSide.AGENT
        // This is a simplified conversion. You might need more fields.
        // The existing ChatMessage in common.chat seems to be an interface/sealed class.
        // We need to map to ChatMessageText or other appropriate types.
        return ChatMessageText(content = appMessage.content, side = side, accelerator = model.getStringConfigValue(ConfigKey.ACCELERATOR, ""))
    }


    // Override or adapt ChatViewModel's addMessage
    override fun addMessage(model: Model, message: com.google.ai.edge.gallery.ui.common.chat.ChatMessage) {
       val conversation = _currentConversation.value ?: return
       val role = if (message.side == ChatSide.USER) ChatMessageRole.USER else ChatMessageRole.ASSISTANT

       // Only add ChatMessageText for now to conversation history. Loading/Error messages are transient.
       if (message is ChatMessageText) {
           val appChatMessage = com.google.ai.edge.gallery.data.ChatMessage(
               id = UUID.randomUUID().toString(),
               conversationId = conversation.id,
               timestamp = System.currentTimeMillis(),
               role = role,
               content = message.content,
               personaUsedId = conversation.activePersonaId
           )
           val updatedMessages = conversation.messages.toMutableList().apply { add(appChatMessage) }
           _currentConversation.value = conversation.copy(
               messages = updatedMessages,
               lastModifiedTimestamp = System.currentTimeMillis()
           )
           // Save updated conversation
           viewModelScope.launch {
               _currentConversation.value?.let { dataStoreRepository.updateConversation(it) }
           }
       }
       // Update the UI-specific message list
       _uiMessages.value = _uiMessages.value.toMutableList().apply { add(message) }
    }

    // Adapt generateResponse
    fun generateChatResponse(model: Model, userInput: String, image: Bitmap? = null) { // Renamed to avoid conflict if base still used
       val currentConvo = _currentConversation.value
       if (currentConvo == null) {
           Log.e(TAG, "Cannot generate response, no active conversation.")
           // Optionally, start a new conversation implicitly or show an error
           return
       }

       // Add user's message to conversation and UI
       val userUiMessage = ChatMessageText(content = userInput, side = ChatSide.USER, accelerator = model.getStringConfigValue(ConfigKey.ACCELERATOR, ""))
       addMessage(model, userUiMessage) // This will also save it to DataStore

       // The rest is similar to original generateResponse, but uses currentConvo
       val accelerator = model.getStringConfigValue(key = ConfigKey.ACCELERATOR, defaultValue = "")
       viewModelScope.launch(Dispatchers.Default) {
           setInProgress(true) // From base ChatViewModel
           setPreparing(true)  // From base ChatViewModel

           addMessage(model, ChatMessageLoading(accelerator = accelerator)) // Show loading in UI

           while (model.instance == null) { delay(100) } // Wait for model instance
           delay(500)

           val instance = model.instance as LlmModelInstance
           // History is now part of the session, primed by startNewConversation or loadConversation.
           // LlmChatModelHelper.runInference will just add the latest userInput.

           try {
               LlmChatModelHelper.runInference(
                   model = model,
                   input = userInput, // Just the new input
                   image = image, // Handle image if provided
                   resultListener = { partialResult, done ->
                       // UI update logic for streaming response - largely same as original
                       // Ensure to use 'addMessage' or similar to update _uiMessages
                       // and save assistant's final message to _currentConversation
                       val lastUiMsg = _uiMessages.value.lastOrNull()
                       if (lastUiMsg?.type == ChatMessageType.LOADING) {
                           _uiMessages.value = _uiMessages.value.dropLast(1)
                            // Add an empty message that will receive streaming results for UI
                           addMessage(model, ChatMessageText(content = "", side = ChatSide.AGENT, accelerator = accelerator))
                       }

                       val currentAgentMessage = _uiMessages.value.lastOrNull() as? ChatMessageText
                       if (currentAgentMessage != null) {
                          _uiMessages.value = _uiMessages.value.dropLast(1) + currentAgentMessage.copy(content = currentAgentMessage.content + partialResult)
                       }


                       if (done) {
                           setInProgress(false)
                           val finalAssistantContent = (_uiMessages.value.lastOrNull() as? ChatMessageText)?.content ?: ""
                           // Add final assistant message to DataStore Conversation
                           val assistantAppMessage = com.google.ai.edge.gallery.data.ChatMessage(
                               id = UUID.randomUUID().toString(),
                               conversationId = currentConvo.id,
                               timestamp = System.currentTimeMillis(),
                               role = ChatMessageRole.ASSISTANT,
                               content = finalAssistantContent,
                               personaUsedId = currentConvo.activePersonaId
                           )
                           val updatedMessages = currentConvo.messages.toMutableList().apply { add(assistantAppMessage) }
                           _currentConversation.value = currentConvo.copy(
                               messages = updatedMessages,
                               lastModifiedTimestamp = System.currentTimeMillis()
                           )
                           viewModelScope.launch {  _currentConversation.value?.let { dataStoreRepository.updateConversation(it) } }
                           // Update benchmark results if necessary (code omitted for brevity, but similar logic as original generateResponse)
                           val lastMessage = _uiMessages.value.lastOrNull { it.side == ChatSide.AGENT } // get the agent's message
                           if (lastMessage is ChatMessageText && STATS.isNotEmpty()) { // Assuming STATS is defined
                                // This part needs to be adapted. The original `generateResponse` calculated these.
                                // For now, we'll omit direct benchmark updates here to simplify,
                                // as they depend on variables (timeToFirstToken, prefillSpeed, etc.)
                                // not directly available in this refactored `generateChatResponse` structure
                                // without significant further adaptation of the LlmChatModelHelper.runInference callback.
                                // A simpler approach might be to just mark the message as not running.
                                 val updatedAgentMessage = lastMessage.copy(
                                     llmBenchmarkResult = lastMessage.llmBenchmarkResult?.copy(running = false)
                                         ?: ChatMessageBenchmarkLlmResult(orderedStats = STATS, statValues = mutableMapOf(), running = false, latencyMs = -1f, accelerator = accelerator)
                                 )
                                 val finalUiMessages = _uiMessages.value.toMutableList()
                                 val agentMsgIndex = finalUiMessages.indexOfLast { it.id == lastMessage.id }
                                 if(agentMsgIndex != -1) {
                                     finalUiMessages[agentMsgIndex] = updatedAgentMessage
                                     _uiMessages.value = finalUiMessages
                                 }
                           }
                       }
                   },
                   cleanUpListener = {
                       setInProgress(false)
                       setPreparing(false)
                   }
               )
           } catch (e: Exception) {
               Log.e(TAG, "Error in generateChatResponse: ", e)
               setInProgress(false)
               setPreparing(false)
               // Add error message to UI
               addMessage(model, ChatMessageWarning(content = "Error: ${e.message}"))
           }
       }
   }

   // TODO: Override/adapt clearAllMessages, stopResponse, runAgain, handleError from base ChatViewModel
   // to work with the new currentConversation model and _uiMessages.
   // For example, clearAllMessages should clear _uiMessages and potentially currentConvo.messages then save.
   // resetSession should re-prime with system prompt and history if currentConvo exists.
}