package com.google.ai.edge.gallery.ui.llmchat

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.ChatSession
import com.google.ai.edge.gallery.data.ChatSessionRepository
import com.google.ai.edge.gallery.data.EMPTY_MODEL
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.gallery.ui.theme.emptyStateContent
import com.google.ai.edge.gallery.ui.theme.emptyStateTitle
import kotlinx.coroutines.launch

private const val TAG = "AGChatHomeScreen"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatHomeScreen(
  modelManagerViewModel: ModelManagerViewModel,
  onGoToModels: () -> Unit,
  modifier: Modifier = Modifier,
  viewModel: LlmChatViewModel = hiltViewModel(),
) {
  val context = LocalContext.current
  val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
  val scope = rememberCoroutineScope()
  val repository = remember { ChatSessionRepository(context) }

  var sessions by remember { mutableStateOf(repository.loadSessions()) }
  var activeSessionId by remember { mutableStateOf(repository.getActiveChatId()) }

  val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
  val selectedModel = modelManagerUiState.selectedModel

  Log.d(TAG, "Compose: sessions=${sessions.size}, activeSessionId=$activeSessionId, selectedModel=${selectedModel.name}")

  // Refresh sessions whenever the drawer opens so the list is always up-to-date.
  LaunchedEffect(drawerState.currentValue) {
    if (drawerState.currentValue == DrawerValue.Open) {
      Log.d(TAG, "Drawer opened, refreshing sessions")
      sessions = repository.loadSessions()
    }
  }

  LaunchedEffect(activeSessionId) {
    Log.d(TAG, "LaunchedEffect activeSessionId=$activeSessionId")
    repository.setActiveChatId(activeSessionId)
    if (activeSessionId != null) {
      val session = sessions.find { it.id == activeSessionId }
      if (session != null) {
        val task = modelManagerViewModel.getTaskById(id = BuiltInTaskId.LLM_CHAT)
        val sessionModel = task?.models?.find { it.name == session.modelName }
        val targetModel = sessionModel ?: selectedModel
        Log.d(TAG, "Loading session ${session.id}, model=${session.modelName}, sessionModel=${sessionModel?.name}, targetModel=${targetModel.name}, msgs=${session.messages.size}")
        if (sessionModel != null && sessionModel.name != selectedModel.name) {
          Log.d(TAG, "Switching model to ${sessionModel.name}")
          modelManagerViewModel.selectModel(sessionModel)
        }
        viewModel.currentSessionId = session.id
        val uiMessages = session.toUiMessages()
        Log.d(TAG, "Converted to ${uiMessages.size} UI messages")
        viewModel.setMessages(targetModel, uiMessages)
      } else {
        Log.w(TAG, "Session $activeSessionId not found in sessions list (size=${sessions.size})")
      }
    } else {
      Log.d(TAG, "Clearing chat because activeSessionId is null")
      viewModel.currentSessionId = null
      viewModel.clearAllMessages(selectedModel)
    }
  }

  fun refreshSessions() {
    val loaded = repository.loadSessions()
    Log.d(TAG, "refreshSessions: loaded ${loaded.size} sessions")
    sessions = loaded
  }

  fun saveCurrentSession(model: Model) {
    scope.launch {
      val messages = viewModel.uiState.value.messagesByModel[model.name] ?: return@launch
      val dataMessages = messages.toDataMessages()
      Log.d(TAG, "saveCurrentSession: model=${model.name}, uiMsgs=${messages.size}, dataMsgs=${dataMessages.size}")
      if (dataMessages.isEmpty()) return@launch

      val existingSessionId = viewModel.currentSessionId
      val existingSession = existingSessionId?.let { id -> sessions.find { it.id == id } }

      val sessionId =
        if (existingSession != null && existingSession.modelName == model.name) {
          Log.d(TAG, "Reusing existing session $existingSessionId")
          existingSessionId
        } else {
          val newId = generateSessionId()
          Log.d(TAG, "Creating new session $newId (existingSession=${existingSession?.id}, modelMatch=${existingSession?.modelName == model.name})")
          newId
        }

      if (viewModel.currentSessionId != sessionId) {
        viewModel.currentSessionId = sessionId
        activeSessionId = sessionId
      }

      val session =
        ChatSession(
          id = sessionId,
          title = generateChatTitle(dataMessages),
          updatedAt = java.time.Instant.now().toString(),
          messages = dataMessages,
          modelName = model.name,
        )
      repository.upsertSession(session)
      Log.d(TAG, "Upserted session $sessionId with ${dataMessages.size} messages")
      refreshSessions()
    }
  }

  val navigationIcon: @Composable (() -> Unit) = {
    IconButton(onClick = { scope.launch { drawerState.open() } }) {
      Icon(Icons.Rounded.Menu, contentDescription = "Menu")
    }
  }

  ModalNavigationDrawer(
    drawerState = drawerState,
    drawerContent = {
      ModalDrawerSheet(modifier = Modifier.width(300.dp)) {
        ChatHistoryDrawer(
          sessions = sessions,
          activeChatId = activeSessionId,
          onNewChat = {
            Log.d(TAG, "onNewChat clicked")
            activeSessionId = null
            viewModel.currentSessionId = null
            viewModel.clearAllMessages(selectedModel)
            scope.launch { drawerState.close() }
          },
          onOpenSession = { sessionId ->
            Log.d(TAG, "onOpenSession: $sessionId")
            activeSessionId = sessionId
            scope.launch { drawerState.close() }
          },
          onDeleteSession = { sessionId ->
            Log.d(TAG, "onDeleteSession: $sessionId")
            repository.deleteSession(sessionId)
            if (activeSessionId == sessionId) {
              activeSessionId = null
              viewModel.currentSessionId = null
              viewModel.clearAllMessages(selectedModel)
            }
            refreshSessions()
          },
        )
      }
    },
  ) {
    LlmChatScreen(
      modelManagerViewModel = modelManagerViewModel,
      navigateUp = {},
      modifier = modifier,
      viewModel = viewModel,
      showImagePicker = true,
      showAudioPicker = true,
      navigationIcon = navigationIcon,
      onMessagesUpdated = { model -> saveCurrentSession(model) },
      emptyStateComposable = { model ->
        if (model.name == EMPTY_MODEL.name) {
          NoModelEmptyState()
        } else {
          DefaultChatEmptyState()
        }
      },
    )
  }
}

@Composable
private fun NoModelEmptyState() {
  Box(modifier = Modifier.fillMaxSize()) {
    Column(
      modifier =
        Modifier.align(Alignment.TopStart)
          .fillMaxWidth()
          .padding(start = 32.dp, end = 32.dp, top = 88.dp),
      horizontalAlignment = Alignment.Start,
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(stringResource(R.string.aichat_no_model_title), style = emptyStateTitle)
      Text(
        stringResource(R.string.aichat_no_model_content),
        style = emptyStateContent,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Start,
      )
    }
  }
}

@Composable
private fun DefaultChatEmptyState() {
  Box(modifier = Modifier.fillMaxSize()) {
    Column(
      modifier =
        Modifier.align(Alignment.TopStart)
          .fillMaxWidth()
          .padding(start = 32.dp, end = 32.dp, top = 88.dp),
      horizontalAlignment = Alignment.Start,
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(stringResource(R.string.aichat_emptystate_title), style = emptyStateTitle)
      Text(
        stringResource(R.string.aichat_emptystate_content),
        style = emptyStateContent,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Start,
      )
    }
  }
}
