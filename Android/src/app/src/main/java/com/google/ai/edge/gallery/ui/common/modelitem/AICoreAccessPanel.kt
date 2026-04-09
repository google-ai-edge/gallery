/*
 * Copyright 2026 Google LLC
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

package com.google.ai.edge.gallery.ui.common.modelitem

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.google.ai.edge.gallery.R

/**
 * Composable function to display an access panel for AICore models when they require user action
 * (e.g., setup steps, permissions, or dealing with a failed access state).
 */
@Composable
fun AICoreAccessPanel() {
  Row(
    modifier =
      Modifier.fillMaxWidth()
        .clip(RoundedCornerShape(12.dp))
        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
        .padding(16.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      stringResource(R.string.aicore_access_panel_title),
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.bodyMedium,
      modifier = Modifier.weight(1f).padding(end = 12.dp),
    )
    val context = LocalContext.current
    TextButton(
      onClick = {
        val intent =
          Intent(
            Intent.ACTION_VIEW,
            "https://developers.google.com/ml-kit/genai/aicore-dev-preview".toUri(),
          )
        context.startActivity(intent)
      },
      contentPadding = PaddingValues(horizontal = 8.dp),
    ) {
      Text(
        stringResource(R.string.aicore_access_panel_button),
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelLarge,
      )
    }
  }
}
