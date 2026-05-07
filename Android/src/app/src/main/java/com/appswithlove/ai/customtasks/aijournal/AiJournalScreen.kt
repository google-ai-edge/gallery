package com.appswithlove.ai.customtasks.aijournal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.appswithlove.ai.R
import com.appswithlove.ai.ui.modelmanager.ModelManagerViewModel
import kotlinx.coroutines.launch

@Composable
fun AiJournalScreen(
  modelManagerViewModel: ModelManagerViewModel,
  bottomPadding: Dp,
  viewModel: AiJournalViewModel = hiltViewModel(),
) {
  val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
  val model = modelManagerUiState.selectedModel

  if (!modelManagerUiState.isModelInitialized(model = model)) {
    Row(
      modifier = Modifier.fillMaxSize(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.Center,
    ) {
      CircularProgressIndicator(
        trackColor = MaterialTheme.colorScheme.surfaceVariant,
        strokeWidth = 3.dp,
        modifier = Modifier.size(24.dp),
      )
    }
    return
  }

  val pagerState = rememberPagerState(pageCount = { 2 })
  val scope = rememberCoroutineScope()

  Column(modifier = Modifier.fillMaxSize()) {
    TabRow(selectedTabIndex = pagerState.currentPage) {
      Tab(
        selected = pagerState.currentPage == 0,
        onClick = { scope.launch { pagerState.animateScrollToPage(0) } },
        text = { Text(stringResource(R.string.aijournal_tab_chat)) },
        icon = { Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = stringResource(R.string.aijournal_tab_chat)) },
      )
      Tab(
        selected = pagerState.currentPage == 1,
        onClick = { scope.launch { pagerState.animateScrollToPage(1) } },
        text = { Text(stringResource(R.string.aijournal_tab_history)) },
        icon = { Icon(Icons.Outlined.History, contentDescription = stringResource(R.string.aijournal_tab_history)) },
      )
    }

    HorizontalPager(
      state = pagerState,
      modifier = Modifier.fillMaxSize(),
    ) { page ->
      when (page) {
        0 -> AiJournalChatTab(
          model = model,
          viewModel = viewModel,
          bottomPadding = bottomPadding,
        )
        1 -> AiJournalHistoryTab(
          viewModel = viewModel,
          bottomPadding = bottomPadding,
        )
      }
    }
  }
}
