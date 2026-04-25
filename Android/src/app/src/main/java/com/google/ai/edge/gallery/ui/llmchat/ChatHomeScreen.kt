package com.google.ai.edge.gallery.ui.llmchat

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatHomeScreen(
    modelManagerViewModel: ModelManagerViewModel,
    onGoToModels: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LlmChatViewModel = hiltViewModel()
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
    val selectedModel = modelManagerUiState.selectedModel
    val context = LocalContext.current

    // Check if model is truly loaded and initialized
    val isModelLoaded = selectedModel.name.isNotEmpty()
            && selectedModel.name != "dummy"
            && selectedModel.name != "empty"
            && modelManagerUiState.isModelInitialized(selectedModel)

    // Shared hamburger menu icon
    val navigationIcon: @Composable (() -> Unit) = {
        IconButton(onClick = { scope.launch { drawerState.open() } }) {
            Icon(Icons.Rounded.Menu, contentDescription = "Menu")
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.width(300.dp)) {
                ChatHistoryDrawerContent(
                    onNewChat = {
                        scope.launch { drawerState.close() }
                    },
                    onSelectChat = { sessionId ->
                        scope.launch { drawerState.close() }
                    }
                )
            }
        }
    ) {
        if (isModelLoaded) {
            // ── Model is loaded: show the real chat screen ──
            // Pass our hamburger icon so it replaces the back arrow.
            // The ChatView's own Scaffold + ModelPageAppBar will render,
            // showing the model name, config button, etc.
            LlmChatScreen(
                modelManagerViewModel = modelManagerViewModel,
                navigateUp = {}, // no-op, this is home
                modifier = modifier,
                viewModel = viewModel,
                showImagePicker = true,
                showAudioPicker = true,
                navigationIcon = navigationIcon,
            )
        } else {
            // ── No model loaded: show our branded empty state ──
            EmptyChatState(
                navigationIcon = navigationIcon,
                onGoToModels = onGoToModels,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun EmptyChatState(
    navigationIcon: @Composable (() -> Unit),
    onGoToModels: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var messageText by remember { mutableStateOf("") }

    Scaffold(
        modifier = modifier,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .padding(top = 4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            text = "Chats",
                            style = MaterialTheme.typography.headlineLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "Load a model to start chatting with the\nnative runtime.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row {
                        IconButton(onClick = { /* new chat */ }) {
                            Icon(
                                Icons.Outlined.Edit,
                                contentDescription = "New Chat",
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        navigationIcon()
                    }
                }
            }
        },
        bottomBar = {
            // Persistent input bar at bottom
            ChatInputBar(
                text = messageText,
                onTextChange = { messageText = it },
                onSend = {
                    Toast.makeText(context, "Model is not loaded. Go to Models tab to load one.", Toast.LENGTH_SHORT).show()
                },
                onVoice = {
                    Toast.makeText(context, "Model is not loaded", Toast.LENGTH_SHORT).show()
                },
                onAttach = {
                    Toast.makeText(context, "Model is not loaded", Toast.LENGTH_SHORT).show()
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Lightning bolt icon in rounded rectangle
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Bolt,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp),
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                "How can I help you?",
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                "Select and load a native model first, then\nchat with text, images, documents, or voice.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ChatInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onVoice: () -> Unit,
    onAttach: () -> Unit,
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // File attach button
        IconButton(onClick = {
            Toast.makeText(context, "Load a model first to attach files", Toast.LENGTH_SHORT).show()
        }, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Outlined.AttachFile,
                contentDescription = "Attach File",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }

        // Image picker button
        IconButton(onClick = {
            Toast.makeText(context, "Load a model first to attach images", Toast.LENGTH_SHORT).show()
        }, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Outlined.Image,
                contentDescription = "Attach Image",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }

        // Text input
        BasicTextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            textStyle = TextStyle(
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp,
            ),
            decorationBox = { innerTextField ->
                Box {
                    if (text.isEmpty()) {
                        Text(
                            "Ask anything...",
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            fontSize = 16.sp,
                        )
                    }
                    innerTextField()
                }
            }
        )

        // Mic / Send button
        IconButton(
            onClick = {
                if (text.isEmpty()) {
                    Toast.makeText(context, "Load a model first", Toast.LENGTH_SHORT).show()
                } else {
                    onSend()
                }
            },
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
        ) {
            Icon(
                Icons.Rounded.Mic,
                contentDescription = "Voice",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun ChatHistoryDrawerContent(
    onNewChat: () -> Unit,
    onSelectChat: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            "Chat History",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 16.dp),
        )
        // Placeholder for actual chat history items
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "No chat history yet",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Start a conversation to see it here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }
    }
}
