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

package com.google.ai.edge.gallery.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.core.os.bundleOf
import com.google.ai.edge.gallery.firebaseAnalytics
import com.google.ai.edge.gallery.ui.theme.customColors

@Composable
fun ClickableLink(url: String, linkText: String, icon: ImageVector? = null) {
  val uriHandler = LocalUriHandler.current
  val annotatedText =
    AnnotatedString(
      text = linkText,
      spanStyles =
        listOf(
          AnnotatedString.Range(
            item =
              SpanStyle(
                color = MaterialTheme.customColors.linkColor,
                textDecoration = TextDecoration.Underline,
              ),
            start = 0,
            end = linkText.length,
          )
        ),
    )

  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
    if (icon != null) {
      Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
    }
    Text(
      text = annotatedText,
      textAlign = TextAlign.Center,
      style = MaterialTheme.typography.bodyMedium,
      modifier =
        Modifier.padding(start = 6.dp).clickable {
          uriHandler.openUri(url)
          firebaseAnalytics?.logEvent("resource_link_click", bundleOf("link_destination" to url))
        },
    )
  }
}
