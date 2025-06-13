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

package com.google.ai.edge.gallery.ui.home

import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.ai.edge.gallery.BuildConfig
import com.google.ai.edge.gallery.common.getJsonResponse
import com.google.ai.edge.gallery.ui.common.ClickableLink
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "AGNewReleaseNotifi"
private const val REPO = "google-ai-edge/gallery"

data class ReleaseInfo(val html_url: String, val tag_name: String)

@Composable
fun NewReleaseNotification() {
  var newReleaseVersion by remember { mutableStateOf("") }
  var newReleaseUrl by remember { mutableStateOf("") }
  val lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current
  val coroutineScope = rememberCoroutineScope()

  DisposableEffect(lifecycleOwner) {
    // Create a LifecycleEventObserver to listen for specific lifecycle events.
    val observer = LifecycleEventObserver { _, event ->
      // Log or perform actions based on the lifecycle event.
      when (event) {
        Lifecycle.Event.ON_RESUME -> {
          coroutineScope.launch {
            withContext(Dispatchers.IO) {
              Log.d(TAG, "Checking for new release...")
              val info =
                getJsonResponse<ReleaseInfo>("https://api.github.com/repos/$REPO/releases/latest")
              if (info != null) {
                val curRelease = BuildConfig.VERSION_NAME
                val newRelease = info.jsonObj.tag_name
                val isNewer = isNewerRelease(currentRelease = curRelease, newRelease = newRelease)
                Log.d(TAG, "curRelease: $curRelease, newRelease: $newRelease, isNewer: $isNewer")
                if (isNewer) {
                  newReleaseVersion = newRelease
                  newReleaseUrl = info.jsonObj.html_url
                }
              }
            }
          }
        }

        else -> {}
      }
    }

    lifecycleOwner.lifecycle.addObserver(observer)

    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  AnimatedVisibility(
    visible = newReleaseVersion.isNotEmpty(),
    enter = fadeIn() + expandVertically(),
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
      modifier =
        Modifier.padding(horizontal = 16.dp)
          .padding(bottom = 12.dp)
          .clip(CircleShape)
          .background(MaterialTheme.colorScheme.tertiaryContainer)
          .padding(4.dp),
    ) {
      Text(
        "New release $newReleaseVersion available",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(start = 12.dp),
      )
      Row(
        modifier = Modifier.padding(end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        ClickableLink(
          url = newReleaseUrl,
          linkText = "View",
          icon = Icons.AutoMirrored.Rounded.OpenInNew,
        )
      }
    }
  }
}

private fun isNewerRelease(currentRelease: String, newRelease: String): Boolean {
  // Split the version strings into their individual components (e.g., "0.9.0" -> ["0", "9", "0"])
  val currentComponents = currentRelease.split('.').map { it.toIntOrNull() ?: 0 }
  val newComponents = newRelease.split('.').map { it.toIntOrNull() ?: 0 }

  // Determine the maximum number of components to iterate through
  val maxComponents = max(currentComponents.size, newComponents.size)

  // Iterate through the components from left to right (major, minor, patch, etc.)
  for (i in 0 until maxComponents) {
    val currentComponent = currentComponents.getOrElse(i) { 0 }
    val newComponent = newComponents.getOrElse(i) { 0 }

    if (newComponent > currentComponent) {
      return true
    } else if (newComponent < currentComponent) {
      return false
    }
  }

  return false
}
