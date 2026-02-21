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

package com.google.ai.edge.gallery.ui.common.tos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.ui.common.buildTrackableUrlAnnotatedString

/** A composable for Gemma Terms of Use dialog, shown once before a Gemma model is downloaded. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GemmaTermsOfUseDialog(
  onTosAccepted: () -> Unit,
  onCancel: () -> Unit = {},
  viewingMode: Boolean = false,
) {
  Dialog(onDismissRequest = onCancel) {
    Card(shape = RoundedCornerShape(28.dp)) {
      Column(modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
        // Title.
        val titleColor = MaterialTheme.colorScheme.onSurface
        BasicText(
          stringResource(R.string.tos_dialog_title_gemma),
          modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
          style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Medium),
          color = { titleColor },
          maxLines = 1,
          autoSize =
            TextAutoSize.StepBased(minFontSize = 16.sp, maxFontSize = 24.sp, stepSize = 1.sp),
        )

        Column(modifier = Modifier.verticalScroll(rememberScrollState()).weight(1f, fill = false)) {
          Text(
            buildAnnotatedString {
              append("Gemma models on the Google AI Edge Gallery app are governed by the ")
              append(
                buildTrackableUrlAnnotatedString(
                  url = "https://ai.google.dev/gemma/terms",
                  linkText = "Gemma Terms of Service",
                )
              )
              append(". Please review these terms and ensure you agree before continuing.")
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp),
          )
        }

        Row(
          modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
          horizontalArrangement = Arrangement.End,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          // Cancel button.
          if (!viewingMode) {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
            Spacer(modifier = Modifier.width(8.dp))
          }

          // Accept button.
          Button(onClick = onTosAccepted) {
            Text(
              stringResource(
                if (viewingMode) R.string.close
                else R.string.tos_dialog_agree_and_continue_button_label
              )
            )
          }
        }
      }
    }
  }
}
