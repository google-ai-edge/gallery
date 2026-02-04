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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.ui.common.buildTrackableUrlAnnotatedString

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MobileActionsChallengeDialog(
  onDismiss: () -> Unit,
  onLoadModel: () -> Unit,
  onSendEmail: () -> Unit,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val guideUrl = "https://ai.google.dev/gemma/docs/mobile-actions"

  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
    Column(modifier = Modifier.padding(16.dp)) {
      Text(text = "🏆", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
      Text(
        text = stringResource(R.string.mobile_actions_challenge_title),
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold,
      )
      Text(
        text = stringResource(R.string.mobile_actions_challenge_subtitle),
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
      )
      Spacer(modifier = Modifier.height(16.dp))
      Text(
        text = stringResource(R.string.mobile_actions_challenge_description),
        style = MaterialTheme.typography.bodyMedium,
      )
      Spacer(modifier = Modifier.height(24.dp))
      Text(
        text = stringResource(R.string.mobile_actions_challenge_instructions_title),
        fontWeight = FontWeight.Bold,
      )
      val instructions = buildAnnotatedString {
        append("1. ")
        withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) { append("On your computer") }
        append(", open ")
        append(buildTrackableUrlAnnotatedString(url = guideUrl, linkText = "this guide"))
        append(
          "\n2. Follow the instructions to fine tune the model and convert it to .litertlm format."
        )
        append("\n3. Transfer the file to this phone.")
        append("\n4. Tap ")
        withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) { append("Load Model") }
        append(" below to unlock the demo.")
      }
      Text(
        text = instructions,
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.bodyMedium,
      )
      Spacer(modifier = Modifier.height(16.dp))
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = onSendEmail) {
          Text(stringResource(R.string.mobile_actions_challenge_email_colab))
        }
        Button(onClick = onLoadModel) {
          Text(stringResource(R.string.mobile_actions_challenge_load_model))
        }
      }
    }
  }
}
