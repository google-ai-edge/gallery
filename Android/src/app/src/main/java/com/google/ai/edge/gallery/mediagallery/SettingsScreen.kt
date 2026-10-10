/*
 * Copyright 2026 Pascal Fritzsche
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

package com.google.ai.edge.gallery.mediagallery

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.ai.edge.gallery.BuildConfig
import com.google.ai.edge.gallery.morgenschiss.MORGENSCHISS_BASE_URL
import com.google.ai.edge.gallery.morgenschiss.MediaSyncWorker
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: GalleryViewModel, onBack: () -> Unit, extra: @Composable () -> Unit = {}) {
  val session by viewModel.session.collectAsState()
  val update by viewModel.update.collectAsState()
  val context = LocalContext.current
  LaunchedEffect(session) { if (session != null) viewModel.checkForUpdate() }
  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("Einstellungen") },
        navigationIcon = {
          IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück") }
        },
      )
    }
  ) { padding ->
    Column(
      Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Text("morgenschiss", style = MaterialTheme.typography.titleMedium)
      val current = session
      if (current == null) {
        Text(
          "Mit Anmeldung sucht die Galerie zuerst auf morgenschiss (der Mac rechnet genauer). Ohne bleibt alles auf dem Handy.",
          style = MaterialTheme.typography.bodyMedium,
        )
        LoginForm(viewModel)
      } else {
        Text("Angemeldet als ${current.user}", style = MaterialTheme.typography.bodyMedium)
        SyncStatus(onSync = { viewModel.syncNow() })
        OutlinedButton(onClick = { viewModel.logout() }) { Text("Abmelden") }
      }
      HorizontalDivider()
      Text("App", style = MaterialTheme.typography.titleMedium)
      Text("Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", style = MaterialTheme.typography.bodyMedium)
      update?.let { u ->
        Button(
          onClick = {
            context.startActivity(Intent(Intent.ACTION_VIEW, "$MORGENSCHISS_BASE_URL/api/mediasearch/apk".toUri()))
          }
        ) {
          Text("Update laden (${u.versionCode})")
        }
      }
      extra()
    }
  }
}

@Composable
private fun LoginForm(viewModel: GalleryViewModel) {
  var user by remember { mutableStateOf("") }
  var password by remember { mutableStateOf("") }
  var error by remember { mutableStateOf<String?>(null) }
  var busy by remember { mutableStateOf(false) }
  val scope = rememberCoroutineScope()
  OutlinedTextField(user, { user = it }, label = { Text("Benutzername") }, singleLine = true, modifier = Modifier.fillMaxWidth())
  OutlinedTextField(
    password,
    { password = it },
    label = { Text("Passwort") },
    singleLine = true,
    visualTransformation = PasswordVisualTransformation(),
    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
    modifier = Modifier.fillMaxWidth(),
  )
  error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
  Button(
    enabled = !busy && user.isNotBlank() && password.isNotEmpty(),
    onClick = {
      busy = true
      scope.launch {
        error = viewModel.login(user, password)
        if (error == null) password = ""
        busy = false
      }
    },
  ) {
    Text(if (busy) "Anmelden …" else "Anmelden")
  }
}

@Composable
private fun SyncStatus(onSync: () -> Unit) {
  val context = LocalContext.current
  val infos by
    WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(MediaSyncWorker.UNIQUE_NOW).collectAsState(initial = emptyList())
  val info = infos.firstOrNull()
  val running = info?.state == WorkInfo.State.RUNNING
  val progress = info?.progress
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    when {
      running && progress != null -> {
        val done = progress.getInt(MediaSyncWorker.KEY_DONE, 0)
        val total = progress.getInt(MediaSyncWorker.KEY_TOTAL, 0)
        val phase = progress.getString(MediaSyncWorker.KEY_PHASE) ?: "Abgleich"
        Text(if (total > 0) "$phase: $done von $total" else phase, style = MaterialTheme.typography.bodyMedium)
        if (total > 0) LinearProgressIndicator(progress = { done.toFloat() / total }, modifier = Modifier.fillMaxWidth())
        else LinearProgressIndicator(Modifier.fillMaxWidth())
      }
      info?.state == WorkInfo.State.ENQUEUED && info.runAttemptCount > 0 ->
        Text("Wartet auf morgenschiss (Mac nicht da oder beschäftigt).", style = MaterialTheme.typography.bodyMedium)
      info?.state == WorkInfo.State.ENQUEUED -> Text("Abgleich startet, sobald das Handy im WLAN ist.", style = MaterialTheme.typography.bodyMedium)
      info?.state == WorkInfo.State.SUCCEEDED ->
        Text("Abgleich fertig, ${info.outputData.getInt(MediaSyncWorker.KEY_UPLOADED, 0)} neu hochgeladen.", style = MaterialTheme.typography.bodyMedium)
      info?.state == WorkInfo.State.FAILED -> Text("Abgleich abgebrochen. Bitte neu anmelden.", style = MaterialTheme.typography.bodyMedium)
    }
    OutlinedButton(enabled = !running, onClick = onSync) { Text("Jetzt abgleichen") }
  }
}
