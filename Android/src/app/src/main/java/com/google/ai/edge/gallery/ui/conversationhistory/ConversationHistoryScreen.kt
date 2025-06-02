package com.google.ai.edge.gallery.ui.conversationhistory

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource // Added import
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.google.ai.edge.gallery.R // Added import for R class
import com.google.ai.edge.gallery.data.Conversation
// import com.google.ai.edge.gallery.data.getModelByName // Not strictly needed if modelName is just a string
import com.google.ai.edge.gallery.ui.ViewModelProvider // For ViewModelProvider.Factory
import com.google.ai.edge.gallery.ui.navigation.LlmChatDestination // For navigation route
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationHistoryScreen(
    navController: NavController,
    viewModelFactory: ViewModelProvider.Factory, // Ensure this matches the actual factory name
    viewModel: ConversationHistoryViewModel = viewModel(factory = viewModelFactory)
) {
    val conversations by viewModel.conversations.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.conversation_history_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.user_profile_back_button_desc)) // Reused
                    }
                }
            )
        }
    ) { paddingValues ->
        if (conversations.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.conversation_history_no_conversations))
            }
        } else {
            LazyColumn(
                contentPadding = paddingValues,
                modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(conversations, key = { it.id }) { conversation ->
                    ConversationHistoryItem(
                        conversation = conversation,
                        onItemClick = {
                            val modelName = conversation.modelIdUsed
                            if (modelName != null) {
                                // Navigate using the route that includes conversationId and modelName
                                navController.navigate(
                                    "${LlmChatDestination.routeTemplate}/conversation/${conversation.id}?modelName=${modelName}"
                                )
                            } else {
                                // Fallback or error: modelIdUsed should ideally not be null for conversations created post-update.
                                // Consider navigating to a generic chat or showing an error.
                                // For now, this click might do nothing if modelIdUsed is null.
                                android.util.Log.w("ConvHistory", "modelIdUsed is null for conversation: ${conversation.id}")
                            }
                        },
                        onDeleteClick = { viewModel.deleteConversation(conversation.id) }
                    )
                }
            }
        }
    }
}

@Composable
fun ConversationHistoryItem(
    conversation: Conversation,
    onItemClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    val dateFormatter = remember { SimpleDateFormat("MMM dd, yyyy hh:mm a", Locale.getDefault()) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onItemClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    conversation.title ?: stringResource(R.string.conversation_history_item_title_prefix, dateFormatter.format(Date(conversation.creationTimestamp))),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Model: ${conversation.modelIdUsed ?: stringResource(R.string.chat_default_agent_name)}", // Display model ID or default
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    stringResource(R.string.conversation_history_last_activity_prefix, dateFormatter.format(Date(conversation.lastModifiedTimestamp))),
                    style = MaterialTheme.typography.bodySmall
                )
                conversation.messages.lastOrNull()?.let {
                    Text(
                        "${it.role}: ${it.content.take(80)}${if (it.content.length > 80) "..." else ""}",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            IconButton(onClick = { showDeleteConfirmDialog = true }) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.persona_item_delete_desc)) // Reused
            }
        }
    }
    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text(stringResource(R.string.conversation_history_delete_dialog_title)) },
            text = { Text(stringResource(R.string.conversation_history_delete_dialog_message)) },
            confirmButton = { Button(onClick = { onDeleteClick(); showDeleteConfirmDialog = false }) { Text(stringResource(R.string.conversation_history_delete_dialog_confirm_button)) } },
            dismissButton = { Button(onClick = { showDeleteConfirmDialog = false }) { Text(stringResource(R.string.dialog_cancel_button)) } }
        )
    }
}
