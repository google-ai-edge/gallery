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

package com.google.ai.edge.gallery.claw

import android.content.Intent
import android.provider.Settings
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * Main screen for Claw — the on-device GUI Agent.
 * Users type a task, and Claw reads the screen + performs actions automatically.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClawScreen(onBack: () -> Unit) {
  val agentState by ClawAgent.state.collectAsState()
  val a11yInstance by ClawAccessibilityService.instance.collectAsState()
  val scope = rememberCoroutineScope()
  val context = LocalContext.current
  var inputText by remember { mutableStateOf("") }
  val listState = rememberLazyListState()

  // Auto-scroll to bottom when messages change
  LaunchedEffect(agentState.messages.size) {
    if (agentState.messages.isNotEmpty()) {
      listState.animateScrollToItem(agentState.messages.size - 1)
    }
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Claw")
            if (agentState.isRunning) {
              Spacer(Modifier.width(8.dp))
              Box(
                modifier = Modifier
                  .size(8.dp)
                  .clip(CircleShape)
                  .background(Color(0xFF4CAF50))
              )
              Spacer(Modifier.width(6.dp))
              Text(
                "Step ${agentState.stepCount}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
          }
        },
      )
    },
  ) { padding ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(padding),
    ) {
      // Accessibility Service status
      if (a11yInstance == null) {
        Card(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
          colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
          ),
          shape = RoundedCornerShape(12.dp),
          onClick = {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
              flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
          },
        ) {
          Column(modifier = Modifier.padding(16.dp)) {
            Text(
              "Accessibility Service not enabled",
              style = MaterialTheme.typography.titleSmall,
              fontWeight = FontWeight.SemiBold,
              color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
              "Tap here to open Settings → Accessibility → Claw → Enable",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onErrorContainer,
            )
          }
        }
      }

      // Messages list
      LazyColumn(
        modifier = Modifier
          .weight(1f)
          .fillMaxWidth()
          .padding(horizontal = 16.dp),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        item { Spacer(Modifier.height(8.dp)) }
        items(agentState.messages) { msg ->
          MessageBubble(msg)
        }
        item { Spacer(Modifier.height(8.dp)) }
      }

      // Current action indicator
      if (agentState.isRunning && agentState.lastAction.isNotEmpty()) {
        Text(
          text = "⚡ ${agentState.lastAction}",
          modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
          style = MaterialTheme.typography.bodySmall,
          fontFamily = FontFamily.Monospace,
          color = MaterialTheme.colorScheme.primary,
        )
      }

      // Input bar
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        OutlinedTextField(
          value = inputText,
          onValueChange = { inputText = it },
          placeholder = { Text("Tell Claw what to do...") },
          modifier = Modifier.weight(1f),
          shape = RoundedCornerShape(24.dp),
          singleLine = true,
          enabled = !agentState.isRunning,
        )
        Spacer(Modifier.width(8.dp))
        if (agentState.isRunning) {
          IconButton(
            onClick = { ClawAgent.stop() },
            modifier = Modifier
              .size(48.dp)
              .clip(CircleShape)
              .background(MaterialTheme.colorScheme.error),
          ) {
            Icon(
              Icons.Rounded.Stop,
              contentDescription = "Stop",
              tint = MaterialTheme.colorScheme.onError,
            )
          }
        } else {
          IconButton(
            onClick = {
              if (inputText.isNotBlank()) {
                val task = inputText.trim()
                inputText = ""
                scope.launch {
                  ClawAgent.runTask(task)
                }
              }
            },
            modifier = Modifier
              .size(48.dp)
              .clip(CircleShape)
              .background(MaterialTheme.colorScheme.primary),
            enabled = inputText.isNotBlank() && a11yInstance != null,
          ) {
            Icon(
              Icons.AutoMirrored.Rounded.Send,
              contentDescription = "Send",
              tint = MaterialTheme.colorScheme.onPrimary,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun MessageBubble(msg: ClawAgent.ChatMessage) {
  val isUser = msg.role == "user"
  val isAction = msg.content.startsWith("[") && msg.content.endsWith("]")

  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
  ) {
    Card(
      shape = RoundedCornerShape(
        topStart = 16.dp,
        topEnd = 16.dp,
        bottomStart = if (isUser) 16.dp else 4.dp,
        bottomEnd = if (isUser) 4.dp else 16.dp,
      ),
      colors = CardDefaults.cardColors(
        containerColor = when {
          isUser -> MaterialTheme.colorScheme.primaryContainer
          isAction -> MaterialTheme.colorScheme.surfaceVariant
          else -> MaterialTheme.colorScheme.secondaryContainer
        }
      ),
      modifier = Modifier.fillMaxWidth(0.85f),
    ) {
      Text(
        text = msg.content,
        modifier = Modifier.padding(12.dp),
        fontSize = if (isAction) 12.sp else 14.sp,
        fontFamily = if (isAction) FontFamily.Monospace else FontFamily.Default,
        maxLines = if (isAction) 2 else Int.MAX_VALUE,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}
