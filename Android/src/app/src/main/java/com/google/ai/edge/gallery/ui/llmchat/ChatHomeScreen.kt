package com.google.ai.edge.gallery.ui.llmchat

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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.EMPTY_MODEL
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.gallery.ui.theme.emptyStateContent
import com.google.ai.edge.gallery.ui.theme.emptyStateTitle
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatHomeScreen(
  modelManagerViewModel: ModelManagerViewModel,
  onGoToModels: () -> Unit,
  modifier: Modifier = Modifier,
  viewModel: LlmChatViewModel = hiltViewModel(),
) {
  val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
  val scope = rememberCoroutineScope()

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
          onNewChat = { scope.launch { drawerState.close() } },
          onSelectChat = { scope.launch { drawerState.close() } },
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

@Composable
private fun ChatHistoryDrawerContent(
  onNewChat: () -> Unit,
  onSelectChat: (String) -> Unit,
) {
  Column(
    modifier = Modifier.fillMaxSize().padding(16.dp),
  ) {
    Text(
      "Chat History",
      style = MaterialTheme.typography.headlineSmall,
      color = MaterialTheme.colorScheme.onSurface,
      modifier = Modifier.padding(bottom = 16.dp),
    )
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
