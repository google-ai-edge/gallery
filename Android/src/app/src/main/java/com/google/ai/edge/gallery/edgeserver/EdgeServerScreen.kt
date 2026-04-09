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

package com.google.ai.edge.gallery.edgeserver

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Full-screen control panel for the Edge Server.
 * Shows server status, an on/off toggle, connection URL, and usage hints.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EdgeServerScreen(onBack: () -> Unit) {
  val state by EdgeServerManager.state.collectAsState()
  val context = LocalContext.current
  val clipboard = LocalClipboardManager.current

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("Edge Server") },
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
        .padding(padding)
        .padding(horizontal = 20.dp)
        .verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Spacer(Modifier.height(4.dp))

      // ── Server toggle ──
      Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
          containerColor = if (state.isRunning)
            MaterialTheme.colorScheme.primaryContainer
          else
            MaterialTheme.colorScheme.surfaceVariant,
        ),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(20.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          // Status dot
          Box(
            modifier = Modifier
              .size(12.dp)
              .clip(CircleShape)
              .background(if (state.isRunning) Color(0xFF4CAF50) else Color(0xFFBDBDBD)),
          )
          Spacer(Modifier.width(12.dp))
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = if (state.isRunning) "Server Running" else "Server Stopped",
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.SemiBold,
            )
            if (state.modelName.isNotEmpty()) {
              Text(
                text = "Model: ${state.modelName}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
          Switch(
            checked = state.isRunning,
            onCheckedChange = { enabled ->
              if (enabled) {
                EdgeServerManager.startServer(context)
              } else {
                EdgeServerManager.stopServer(context)
              }
            },
          )
        }
      }

      // ── Connection info ──
      if (state.isRunning) {
        val baseUrl = "http://127.0.0.1:${state.port}/v1"

        Card(
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(16.dp),
        ) {
          Column(modifier = Modifier.padding(20.dp)) {
            Text(
              text = "Connection",
              style = MaterialTheme.typography.titleSmall,
              fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
              Text(
                text = baseUrl,
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f),
              )
              IconButton(onClick = {
                clipboard.setText(AnnotatedString(baseUrl))
                Toast.makeText(context, "URL copied", Toast.LENGTH_SHORT).show()
              }) {
                Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy URL")
              }
            }
          }
        }

        // ── Quick start ──
        Card(
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(16.dp),
        ) {
          Column(modifier = Modifier.padding(20.dp)) {
            Text(
              text = "Quick Start",
              style = MaterialTheme.typography.titleSmall,
              fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            val curlCmd = """curl $baseUrl/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"model":"auto","messages":[{"role":"user","content":"Hello!"}]}'"""
            Text(
              text = curlCmd,
              fontFamily = FontFamily.Monospace,
              fontSize = 12.sp,
              lineHeight = 18.sp,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Text(
              text = "Compatible with any OpenAI client — set the base URL above and use any API key.",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }

        // ── Endpoints ──
        Card(
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(16.dp),
        ) {
          Column(modifier = Modifier.padding(20.dp)) {
            Text(
              text = "Endpoints",
              style = MaterialTheme.typography.titleSmall,
              fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            EndpointRow("GET", "/health", "Server & model status")
            EndpointRow("GET", "/v1/models", "List loaded models")
            EndpointRow("POST", "/v1/chat/completions", "Chat (streaming & non-streaming)")
          }
        }
      }

      Spacer(Modifier.height(24.dp))
    }
  }
}

@Composable
private fun EndpointRow(method: String, path: String, desc: String) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = method,
      fontFamily = FontFamily.Monospace,
      fontSize = 12.sp,
      fontWeight = FontWeight.Bold,
      color = MaterialTheme.colorScheme.primary,
      modifier = Modifier.width(48.dp),
    )
    Text(
      text = path,
      fontFamily = FontFamily.Monospace,
      fontSize = 12.sp,
      modifier = Modifier.weight(1f),
    )
    Text(
      text = desc,
      fontSize = 11.sp,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}
