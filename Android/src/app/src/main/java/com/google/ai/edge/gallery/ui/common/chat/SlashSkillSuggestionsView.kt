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

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.proto.Skill
import kotlin.math.roundToInt

private const val MAX_SUGGESTIONS_HEIGHT_DP = 280

internal data class SlashSkillOverlayRequest(
  val skills: List<Skill>,
  val query: String,
  val onSelect: (Skill) -> Unit,
)

@Stable
class SlashSkillOverlayState internal constructor() {
  internal var overlayCoordinates: LayoutCoordinates? by mutableStateOf(null)
  internal var anchorCoordinates: LayoutCoordinates? by mutableStateOf(null)
  internal var request: SlashSkillOverlayRequest? by mutableStateOf(null)
}

internal val LocalSlashSkillOverlayState = compositionLocalOf<SlashSkillOverlayState?> { null }

/** Creates and remembers a [SlashSkillOverlayState] for hosting floating slash suggestions. */
@Composable
fun rememberSlashSkillOverlayState(): SlashSkillOverlayState = remember { SlashSkillOverlayState() }

/** Marks the container coordinate space used to position floating slash skill suggestions. */
fun Modifier.slashSkillOverlayContainer(state: SlashSkillOverlayState): Modifier =
  onGloballyPositioned {
    state.overlayCoordinates = it
  }

/**
 * Provides an in-window overlay host so slash skill suggestions render directly above the input box
 * inside the same window without resizing the input area or shifting background content, and resize
 * instantaneously without Android window-relocation animations.
 */
@Composable
fun SlashSkillOverlayHost(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
  val state = rememberSlashSkillOverlayState()
  CompositionLocalProvider(LocalSlashSkillOverlayState provides state) {
    Box(modifier = modifier.slashSkillOverlayContainer(state)) {
      content()
      SlashSkillOverlayLayer(state = state)
    }
  }
}

/** Renders the active slash skill suggestion overlay anchored inside a container [state]. */
@Composable
fun SlashSkillOverlayLayer(state: SlashSkillOverlayState) {
  val overlayCoords = state.overlayCoordinates ?: return
  val anchorCoords = state.anchorCoordinates ?: return
  val request = state.request ?: return
  if (!overlayCoords.isAttached || !anchorCoords.isAttached || request.skills.isEmpty()) {
    return
  }

  val overlayWidth = overlayCoords.size.width
  val overlayHeight = overlayCoords.size.height
  val anchorBounds = overlayCoords.localBoundingBoxOf(anchorCoords)
  val anchorWidthPx = anchorBounds.width.roundToInt()
  if (overlayWidth <= 0 || overlayHeight <= 0 || anchorWidthPx <= 0) {
    return
  }

  val anchorWidthDp = with(LocalDensity.current) { anchorWidthPx.toDp() }
  Layout(
    content = {
      SlashSkillSuggestionsView(
        skills = request.skills,
        query = request.query,
        onSelect = request.onSelect,
        modifier = Modifier.width(anchorWidthDp),
      )
    }
  ) { measurables, _ ->
    val childConstraints =
      Constraints(minWidth = anchorWidthPx, maxWidth = anchorWidthPx, maxHeight = overlayHeight)
    val placeable = measurables.single().measure(childConstraints)
    layout(overlayWidth, overlayHeight) {
      val x =
        anchorBounds.left
          .roundToInt()
          .coerceIn(0, (overlayWidth - placeable.width).coerceAtLeast(0))
      val y = (anchorBounds.top.roundToInt() - placeable.height).coerceAtLeast(0)
      placeable.place(x = x, y = y)
    }
  }
}

/** Semantic test tags exposed by [SlashSkillSuggestionsView] for UI tests. */
object SlashSkillSuggestionTestTags {
  const val SUGGESTIONS_BOX = "slash_skill_suggestions_box"

  fun rowTag(skillName: String): String = "slash_skill_suggestion_$skillName"
}

/**
 * Returns [text] with every case-insensitive occurrence of [query] highlighted using
 * [highlightBackgroundColor] and [highlightTextColor].
 */
fun highlightedSlashSkillText(
  text: String,
  query: String,
  highlightBackgroundColor: Color,
  highlightTextColor: Color,
): AnnotatedString {
  if (query.isEmpty() || text.isEmpty()) {
    return AnnotatedString(text)
  }

  return buildAnnotatedString {
    append(text)
    val highlightStyle =
      SpanStyle(background = highlightBackgroundColor, color = highlightTextColor)
    var matchIndex = text.indexOf(query, startIndex = 0, ignoreCase = true)
    while (matchIndex >= 0) {
      val matchEnd = matchIndex + query.length
      addStyle(style = highlightStyle, start = matchIndex, end = matchEnd)
      matchIndex = text.indexOf(query, startIndex = matchEnd, ignoreCase = true)
    }
  }
}

/**
 * Compact floating card displayed above the chat input box listing [skills] that match the user's
 * active `/` slash [query].
 */
@Composable
fun SlashSkillSuggestionsView(
  skills: List<Skill>,
  query: String,
  onSelect: (Skill) -> Unit,
  modifier: Modifier = Modifier,
) {
  if (skills.isEmpty()) {
    return
  }

  val accessibilityDescription = stringResource(R.string.cd_slash_skill_suggestions)
  val highlightBackgroundColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
  val highlightTextColor = MaterialTheme.colorScheme.onPrimaryContainer

  Surface(
    modifier =
      modifier.fillMaxWidth().heightIn(max = MAX_SUGGESTIONS_HEIGHT_DP.dp).semantics {
        contentDescription = accessibilityDescription
        testTag = SlashSkillSuggestionTestTags.SUGGESTIONS_BOX
      },
    shape = RoundedCornerShape(16.dp),
    color = MaterialTheme.colorScheme.surfaceContainerHigh,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
    shadowElevation = 4.dp,
  ) {
    Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
      for (skill in skills) {
        SlashSkillSuggestionRow(
          skill = skill,
          query = query,
          highlightBackgroundColor = highlightBackgroundColor,
          highlightTextColor = highlightTextColor,
          onClick = { onSelect(skill) },
        )
      }
    }
  }
}

@Composable
private fun SlashSkillSuggestionRow(
  skill: Skill,
  query: String,
  highlightBackgroundColor: Color,
  highlightTextColor: Color,
  onClick: () -> Unit,
) {
  Row(
    verticalAlignment = Alignment.Top,
    horizontalArrangement = Arrangement.spacedBy(10.dp),
    modifier =
      Modifier.fillMaxWidth()
        .heightIn(min = 44.dp)
        .clickable(onClick = onClick)
        .padding(horizontal = 14.dp, vertical = 8.dp)
        .testTag(SlashSkillSuggestionTestTags.rowTag(skill.name)),
  ) {
    Icon(
      painter = painterResource(R.drawable.skill),
      contentDescription = null,
      tint = MaterialTheme.colorScheme.primary,
      modifier = Modifier.padding(top = 2.dp).size(18.dp),
    )

    Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
      val highlightedName =
        highlightedSlashSkillText(
          text = skill.name,
          query = query,
          highlightBackgroundColor = highlightBackgroundColor,
          highlightTextColor = highlightTextColor,
        )
      Text(
        text =
          buildAnnotatedString {
            append('/')
            append(highlightedName)
          },
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )

      if (skill.description.isNotEmpty()) {
        Text(
          text =
            highlightedSlashSkillText(
              text = skill.description,
              query = query,
              highlightBackgroundColor = highlightBackgroundColor,
              highlightTextColor = highlightTextColor,
            ),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}
