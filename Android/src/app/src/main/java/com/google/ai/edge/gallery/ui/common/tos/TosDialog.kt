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

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.ui.common.MarkdownText

/** A composable for Terms of Service dialog, shown once when app is launched. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TosDialog(onTosAccepted: () -> Unit, viewingMode: Boolean = false) {
  var viewFullTerms by remember { mutableStateOf(viewingMode) }

  Dialog(
    properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    onDismissRequest = { if (viewingMode) onTosAccepted() },
  ) {
    Card(shape = RoundedCornerShape(28.dp)) {
      Column(modifier = Modifier.padding(horizontal = 24.dp)) {
        // Title.
        val titleColor = MaterialTheme.colorScheme.onSurface
        BasicText(
          stringResource(R.string.tos_dialog_title),
          modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
          style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Medium),
          color = { titleColor },
          maxLines = 1,
          autoSize =
            TextAutoSize.StepBased(minFontSize = 16.sp, maxFontSize = 24.sp, stepSize = 1.sp),
        )

        Column(modifier = Modifier.verticalScroll(rememberScrollState()).weight(1f, fill = false)) {
          // Short content.
          MarkdownText(
            "By using this app, you agree to the " +
              "[Google Terms of Service](https://policies.google.com/terms?hl=en-US).\n\n" +
              "To learn what information we collect and why, how we use it, " +
              "and how to review and update it, please review the " +
              "[Google Privacy Policy](https://policies.google.com/privacy?hl=en-US).",
            smallFontSize = true,
            textColor = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp),
          )
        }

        // Accept button.
        Button(
          onClick = onTosAccepted,
          modifier = Modifier.padding(top = 28.dp, bottom = 24.dp).align(Alignment.End),
        ) {
          Text(
            stringResource(
              if (viewingMode) R.string.close else R.string.tos_dialog_view_accept_button_label
            )
          )
        }
      }
    }
  }
}
