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

package com.google.ai.edge.gallery.ui.common.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.rounded.AddComment
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R

/**
 * Context compaction banner displaying token limit reached prompt or compression in-progress status
 * above the chat message input.
 */
@Composable
fun ContextCompactionBanner(
  status: ContextCompactionStatus,
  onCompressClicked: (defaultToBehavior: Boolean) -> Unit,
  onNewChatClicked: () -> Unit,
  onDismissClicked: () -> Unit,
  modifier: Modifier = Modifier,
  initialDefaultToBehavior: Boolean = false,
  onDefaultToBehaviorChanged: ((Boolean) -> Unit)? = null,
) {
  var defaultToBehavior by
    remember(status, initialDefaultToBehavior) { mutableStateOf(initialDefaultToBehavior) }

  AnimatedVisibility(
    visible = status != ContextCompactionStatus.IDLE,
    enter = expandVertically() + fadeIn(),
    exit = shrinkVertically() + fadeOut(),
    modifier = modifier,
  ) {
    Card(
      shape = RoundedCornerShape(16.dp),
      colors =
        CardDefaults.cardColors(
          containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
        ),
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
      AnimatedContent(targetState = status, label = "ContextCompactionBannerState") { currentStatus
        ->
        when (currentStatus) {
          ContextCompactionStatus.TOKEN_LIMIT_REACHED -> {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
              ) {
                Text(
                  text =
                    AnnotatedString.fromHtml(stringResource(R.string.token_limit_reached_message)),
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.weight(1f).padding(end = 8.dp),
                )
                IconButton(onClick = onDismissClicked, modifier = Modifier.size(24.dp)) {
                  Icon(
                    Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.close),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              }

              Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                  Modifier.padding(top = 8.dp).clickable {
                    defaultToBehavior = !defaultToBehavior
                    onDefaultToBehaviorChanged?.invoke(defaultToBehavior)
                  },
              ) {
                Checkbox(
                  checked = defaultToBehavior,
                  onCheckedChange = {
                    defaultToBehavior = it
                    onDefaultToBehaviorChanged?.invoke(it)
                  },
                  modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                  text = stringResource(R.string.token_limit_default_behavior_checkbox),
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }

              val textMeasurer = rememberTextMeasurer()
              val density = LocalDensity.current
              val baseButtonTextStyle = MaterialTheme.typography.labelLarge
              val compressLabel = stringResource(R.string.compress_and_continue_button)
              val newChatLabel = stringResource(R.string.new_chat_button)

              BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                val measuredTextWidthPx =
                  textMeasurer
                    .measure(
                      text = compressLabel,
                      style = baseButtonTextStyle,
                      maxLines = 1,
                      softWrap = false,
                    )
                    .size
                    .width +
                    textMeasurer
                      .measure(
                        text = newChatLabel,
                        style = baseButtonTextStyle,
                        maxLines = 1,
                        softWrap = false,
                      )
                      .size
                      .width

                val fullChromeWidthPx =
                  with(density) {
                    4 * 12.dp.roundToPx() +
                      2 * 18.dp.roundToPx() +
                      2 * 6.dp.roundToPx() +
                      8.dp.roundToPx()
                  }
                val fitsFullSpec = measuredTextWidthPx + fullChromeWidthPx <= constraints.maxWidth

                val horizontalPadding = if (fitsFullSpec) 12.dp else 8.dp
                val iconSize = if (fitsFullSpec) 18.dp else 16.dp
                val iconToLabelGap = if (fitsFullSpec) 6.dp else 4.dp
                val buttonSpacing = if (fitsFullSpec) 8.dp else 6.dp

                val compactChromeWidthPx =
                  with(density) {
                    4 * horizontalPadding.roundToPx() +
                      2 * iconSize.roundToPx() +
                      2 * iconToLabelGap.roundToPx() +
                      buttonSpacing.roundToPx()
                  }
                val availableTextWidthPx =
                  (constraints.maxWidth - compactChromeWidthPx - with(density) { 2.dp.roundToPx() })
                    .coerceAtLeast(0)
                val textScale =
                  if (fitsFullSpec || measuredTextWidthPx <= 0) {
                    1f
                  } else {
                    (availableTextWidthPx.toFloat() / measuredTextWidthPx.toFloat()).coerceIn(
                      0.75f,
                      1f,
                    )
                  }
                val buttonTextStyle =
                  if (textScale < 1f) {
                    baseButtonTextStyle.copy(
                      fontSize = baseButtonTextStyle.fontSize * textScale,
                      lineHeight = baseButtonTextStyle.lineHeight * textScale,
                    )
                  } else {
                    baseButtonTextStyle
                  }

                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.spacedBy(buttonSpacing),
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  FilledTonalButton(
                    onClick = { onCompressClicked(defaultToBehavior) },
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    modifier = Modifier.height(32.dp),
                    contentPadding = PaddingValues(horizontal = horizontalPadding, vertical = 2.dp),
                  ) {
                    Icon(
                      Icons.Filled.Compress,
                      contentDescription = null,
                      modifier = Modifier.size(iconSize),
                    )
                    Spacer(modifier = Modifier.width(iconToLabelGap))
                    Text(
                      text = compressLabel,
                      style = buttonTextStyle,
                      maxLines = 1,
                      softWrap = false,
                      overflow = TextOverflow.Ellipsis,
                    )
                  }

                  TextButton(
                    onClick = onNewChatClicked,
                    modifier = Modifier.height(32.dp),
                    contentPadding = PaddingValues(horizontal = horizontalPadding, vertical = 2.dp),
                  ) {
                    Icon(
                      Icons.Rounded.AddComment,
                      contentDescription = null,
                      modifier = Modifier.size(iconSize),
                      tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.width(iconToLabelGap))
                    Text(
                      text = newChatLabel,
                      style = buttonTextStyle,
                      color = MaterialTheme.colorScheme.primary,
                      maxLines = 1,
                      softWrap = false,
                      overflow = TextOverflow.Ellipsis,
                    )
                  }
                }
              }
            }
          }

          ContextCompactionStatus.COMPACTING -> {
            Row(
              modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
              horizontalArrangement = Arrangement.Center,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
              )
              Spacer(modifier = Modifier.width(10.dp))
              Text(
                text = stringResource(R.string.compressing_context),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
              )
            }
          }

          ContextCompactionStatus.IDLE -> {}
        }
      }
    }
  }
}
