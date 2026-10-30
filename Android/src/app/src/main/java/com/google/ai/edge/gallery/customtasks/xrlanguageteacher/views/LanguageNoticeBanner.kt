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

package com.google.ai.edge.gallery.customtasks.xrlanguageteacher.views

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R

/**
 * Light blue note with [message] and, when [onChangeModels] is set, a button labeled [actionLabel]
 * (by default "Switch ASR/TTS models") that calls it. Used under the language dropdowns and on the
 * Saved vocabulary tab when the current TTS model can't play some words.
 */
@Composable
internal fun LanguageNoticeBanner(
  message: String,
  onChangeModels: (() -> Unit)?,
  modifier: Modifier = Modifier,
  actionLabel: String = stringResource(R.string.xrteacher_switch_speech_models),
) {
  Surface(
    shape = RoundedCornerShape(12.dp),
    color = MaterialTheme.colorScheme.primaryContainer,
    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    modifier = modifier.fillMaxWidth(),
  ) {
    Column(modifier = Modifier.padding(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 4.dp)) {
      Text(text = message, style = MaterialTheme.typography.bodySmall)
      if (onChangeModels != null) {
        TextButton(onClick = onChangeModels, modifier = Modifier.align(Alignment.End)) {
          Text(text = actionLabel)
        }
      } else {
        Spacer(modifier = Modifier.height(8.dp))
      }
    }
  }
}
