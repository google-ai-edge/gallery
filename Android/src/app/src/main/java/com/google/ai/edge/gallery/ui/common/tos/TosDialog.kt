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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
          "${stringResource(R.string.tos_dialog_title_app_name)}\n${stringResource(R.string.tos_dialog_title_tos)}",
          modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
          style = MaterialTheme.typography.headlineSmall,
          color = { titleColor },
          maxLines = 2,
          autoSize =
            TextAutoSize.StepBased(minFontSize = 16.sp, maxFontSize = 24.sp, stepSize = 1.sp),
        )

        Column(modifier = Modifier.verticalScroll(rememberScrollState()).weight(1f, fill = false)) {
          // Short content.
          MarkdownText(
            "To use Google AI Edge Gallery, you accept " +
              "(1) the [Google Terms of Service](https://policies.google.com/terms), and (2) " +
              "these Google AI Edge Gallery App Additional Terms of Service. Please read these " +
              "documents carefully. Together, these documents are known as the “Terms”. They " +
              "establish what you can expect from us as you use our " +
              "[services](https://policies.google.com/terms/service-specific?hl=en-US), " +
              "and what we expect from you.",
            smallFontSize = true,
            textColor = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp),
          )

          // Long content.
          if (viewFullTerms) {
            MarkdownText(
              "In addition, your use of any Gemma models in the Google AI Edge Gallery app is " +
                "governed by the [Gemma Terms of Use](https://ai.google.dev/gemma/terms), " +
                "including the [Gemma Prohibited Use Policy]" +
                "(https://ai.google.dev/gemma/prohibited_use_policy). By using, reproducing, " +
                "modifying, distributing, performing, or displaying any portion or element of " +
                "Gemma or any Gemma model derivatives, you agree to be bound by [those terms]" +
                "(https://ai.google.dev/gemma/terms) and that policy.\n" +
                "\n" +
                "Your use of any other AI models in Google AI Edge Gallery is subject to " +
                "applicable terms and licenses, as described in the “Other content” section of " +
                "the [Google Terms of Service](https://policies.google.com/terms). Please read " +
                "those terms carefully before using any third-party model. You are responsible " +
                "for your use of any third-party offerings and for complying with their " +
                "applicable terms. Google does not operate, control, or endorse these " +
                "offerings, and is not responsible or liable for them.\n" +
                "\n" +
                "Google AI Edge Gallery may collect anonymous usage data about your use of the " +
                "app and share such data with Google. We encourage you to read our " +
                "[Privacy Policy](https://policies.google.com/privacy). It explains what " +
                "information we collect, why we collect it, and how you can " +
                "[update, manage, export, and delete your information](http://account.google.com).",
              smallFontSize = true,
              modifier = Modifier.padding(top = 14.dp),
            )
          }
        }

        // Toggle to view full terms.
        if (!viewFullTerms) {
          Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier =
              Modifier.fillMaxWidth().padding(top = 16.dp).clickable { viewFullTerms = true },
          ) {
            Text(
              stringResource(R.string.tos_dialog_view_full_tos),
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            )
            Icon(
              Icons.Filled.KeyboardArrowDown,
              modifier = Modifier.size(24.dp),
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
              contentDescription = "",
            )
          }
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
