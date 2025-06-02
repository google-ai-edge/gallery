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

import android.graphics.Bitmap
import androidx.compose.foundation.layout.* // For Column, Row, Spacer, padding, etc.
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.* // For TextField, TopAppBar, etc.
import androidx.compose.runtime.* // For remember, mutableStateOf, collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource // For string resources
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.ai.edge.gallery.data.Model // Ensure Model is imported
import com.google.ai.edge.gallery.ui.ViewModelProvider
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageImage
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageText
import com.google.ai.edge.gallery.ui.common.chat.ChatView
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import kotlinx.serialization.Serializable

/** Navigation destination data */
object LlmChatDestination {
  @Serializable // Keep serializable if used in NavType directly, though we'll use String for nav args
  const val routeTemplate = "LlmChatRoute"
  const val conversationIdArg = "conversationId"
  // Route for opening an existing conversation
  val routeForConversation = "$routeTemplate/conversation/{$conversationIdArg}"
  // Route for starting a new chat, potentially with a pre-selected model
  const val modelNameArg = "modelName"
  val routeForNewChatWithModel = "$routeTemplate/new/{$modelNameArg}"
  val routeForNewChat = routeTemplate // General new chat
}

@Composable
fun LlmChatScreen(
    modelManagerViewModel: ModelManagerViewModel,
    navigateUp: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LlmChatViewModel = viewModel(factory = ViewModelProvider.Factory),
    conversationId: String? = null // New parameter
) {
    var customSystemPromptInput by remember { mutableStateOf("") }
    val currentConversation by viewModel.currentConversation.collectAsState()
    val activePersona by viewModel.activePersona.collectAsState()
    val uiMessages by viewModel.uiMessages.collectAsState() // Observe uiMessages

    // Prioritize model from conversation if available, then from ModelManagerViewModel
    val selectedModel: Model? = remember(currentConversation, modelManagerViewModel.getSelectedModel(viewModel.task.type)) {
        modelManagerViewModel.getSelectedModel(viewModel.task.type)
    }

    LaunchedEffect(conversationId, selectedModel) {
        if (selectedModel == null) {
            android.util.Log.e("LlmChatScreen", "No model selected for chat.")
            return@LaunchedEffect
        }
        if (conversationId != null) {
            viewModel.loadConversation(conversationId, selectedModel)
        } else {
            if (currentConversation == null || (currentConversation?.id == null && currentConversation?.messages.isNullOrEmpty())) {
                // Let user type system prompt. startNewConversation will be called on first send.
            }
        }
    }

    ChatViewWrapper(
        viewModel = viewModel,
        modelManagerViewModel = modelManagerViewModel,
        navigateUp = navigateUp,
        navController = navController, // Pass navController
        modifier = modifier,
        customSystemPromptInput = customSystemPromptInput,
        onCustomSystemPromptChange = { customSystemPromptInput = it },
        activePersonaName = activePersona?.name ?: currentConversation?.activePersonaId?.let { "ID: $it" } // Fallback to ID if name not loaded
    )
}

// Removed duplicated ChatViewWrapper call and imports that were above it.
// The ExperimentalMaterial3Api annotation is kept for the actual ChatViewWrapper below.

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatViewWrapper(
    viewModel: LlmChatViewModel,
    modelManagerViewModel: ModelManagerViewModel,
    navigateUp: () -> Unit,
    navController: NavController, // Added navController parameter
    modifier: Modifier = Modifier,
    customSystemPromptInput: String,
    onCustomSystemPromptChange: (String) -> Unit,
    activePersonaName: String?
) {
    val context = LocalContext.current
    val currentConvo by viewModel.currentConversation.collectAsState()
    val messagesForUi by viewModel.uiMessages.collectAsState()
    val selectedModel = modelManagerViewModel.getSelectedModel(viewModel.task.type)

    // Show system prompt input if no conversation has started or if it's a new, empty conversation
    val showSystemPromptInput = currentConvo == null || (currentConvo?.id != null && currentConvo!!.messages.isEmpty())


    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(activePersonaName ?: stringResource(viewModel.task.agentNameRes))
            },
            navigationIcon = {
                IconButton(onClick = navigateUp) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.user_profile_back_button_desc)) // Reused
                }
            },
            actions = {
                IconButton(onClick = { navController.navigate(ConversationHistoryDestination.route) }) {
                    Icon(Icons.Filled.History, contentDescription = stringResource(R.string.chat_history_button_desc))
                }
            }
        )

        if (showSystemPromptInput) {
            OutlinedTextField(
                value = customSystemPromptInput,
                onValueChange = onCustomSystemPromptChange,
                label = { Text(stringResource(R.string.chat_custom_system_prompt_label)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                maxLines = 3
            )
        }

        ChatView(
            task = viewModel.task,
            viewModel = viewModel,
            modelManagerViewModel = modelManagerViewModel,
            messages = messagesForUi,
            onSendMessage = { modelFromChatView, userMessages ->
                val userInputMessage = userMessages.firstNotNullOfOrNull { it as? ChatMessageText }?.content ?: ""
                val imageBitmap = userMessages.firstNotNullOfOrNull { it as? ChatMessageImage }?.bitmap

                if (userInputMessage.isNotBlank() || imageBitmap != null) {
                    selectedModel?.let { validSelectedModel ->
                        modelManagerViewModel.addTextInputHistory(userInputMessage)

                        if (currentConvo == null || (currentConvo?.id != null && currentConvo!!.messages.isEmpty() && !currentConvo!!.initialSystemPrompt.isNullOrEmpty().not() && customSystemPromptInput.isBlank())) {
                            viewModel.startNewConversation(
                                customSystemPrompt = if (customSystemPromptInput.isNotBlank()) customSystemPromptInput else null,
                                selectedPersonaId = viewModel.activePersona.value?.id,
                                title = userInputMessage.take(30).ifBlank { stringResource(R.string.chat_new_conversation_title_prefix) }, // Use string resource
                                selectedModel = validSelectedModel
                            )
                        }
                        viewModel.generateChatResponse(model = validSelectedModel, input = userInputMessage, image = imageBitmap)
                    } ?: run {
                        android.util.Log.e("ChatViewWrapper", "No model selected, cannot send message.")
                        // Potentially show error to user
                    }
                }
            },
            // TODO: Add confirmation dialog for reset session
            onResetSessionClicked = { model ->
                 selectedModel?.let {
                    onCustomSystemPromptChange("")
                    viewModel.startNewConversation(
                        customSystemPrompt = null,
                        selectedPersonaId = viewModel.activePersona.value?.id,
                        title = stringResource(R.string.chat_new_conversation_title_prefix), // Use string resource
                        selectedModel = it
                    )
                 }
            },
            showStopButtonInInputWhenInProgress = true,
            onStopButtonClicked = { model -> viewModel.stopResponse(model) },
            navigateUp = navigateUp
        )
    }
}