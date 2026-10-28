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

package com.google.ai.edge.gallery.customtasks.xrlanguageteacher.views.studyview

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.getLanguageName
import com.google.ai.edge.gallery.proto.Language
import com.google.ai.edge.gallery.ui.common.InfoTooltipIconButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The start screen of a vocab game or study activity, shown before the activity itself: the [title]
 * and [subtitle], the [steps] of how to play, a card of activity settings, and a Medium action
 * button. The activity only starts when the player taps the button.
 *
 * Soft decorative shapes float behind the content; they spring in when the screen opens and then
 * drift gently.
 *
 * @param steps the how-to-play steps, in order; each is shown as one numbered line. A step may wrap
 *   its lead phrase in `<b>` tags to show it in bold.
 * @param isPlayEnabled whether the Play button can be tapped.
 * @param lockedMessage if not null, why the game cannot be played with the current settings; it is
 *   shown above the disabled Play button.
 * @param onBack called by the Back button; the system Back is left to the caller.
 * @param languageSetting the language menu, usually a [VocabGameDropdown]; it gets the first row of
 *   the settings card to itself.
 * @param moreSettings the other setting menus, usually [VocabGameDropdown]s; they share the second
 *   row of the settings card and wrap if they do not fit.
 * @param playLabel the label of the button that starts the activity; "Play game" by default.
 * @param playIcon the icon of that button; a game controller by default.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
fun VocabGameStartScreen(
  title: String,
  subtitle: String,
  steps: List<String>,
  isPlayEnabled: Boolean,
  lockedMessage: String?,
  onBack: () -> Unit,
  onPlay: () -> Unit,
  modifier: Modifier = Modifier,
  playLabel: String = stringResource(R.string.xrteacher_game_play),
  playIcon: ImageVector = Icons.Filled.SportsEsports,
  languageSetting: @Composable () -> Unit,
  moreSettings: @Composable () -> Unit,
) {
  val background = MaterialTheme.colorScheme.surfaceContainerLowest
  BoxWithConstraints(modifier = modifier.fillMaxSize().background(background)) {
    VocabGameStartBackground(screenWidth = maxWidth, fadeColor = background)
    // The spacing is tight on purpose, so the whole screen fits on most phones without scrolling.
    Column(
      modifier =
        Modifier.fillMaxWidth()
          .verticalScroll(rememberScrollState())
          // Centers the content on tall screens, and scrolls it on short ones.
          .heightIn(min = maxHeight)
          .padding(start = 16.dp, end = 16.dp, top = BACK_ROW_HEIGHT, bottom = 16.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        Text(
          text = title,
          style =
            MaterialTheme.typography.displayLarge.copy(
              fontSize = 64.sp,
              lineHeight = 68.sp,
              fontWeight = FontWeight.Medium,
            ),
          color = MaterialTheme.colorScheme.onSurface,
          textAlign = TextAlign.Center,
          // The narrow width puts each word of a two-word game name on its own line.
          modifier = Modifier.widthIn(max = 240.dp).semantics { heading() },
        )
        Text(
          text = subtitle,
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
        )
      }
      VocabGameHowToPlay(steps = steps)
      Surface(
        shape = RoundedCornerShape(32.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Column(
          modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          languageSetting()
          FlowRow(
            horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth(),
          ) {
            moreSettings()
          }
        }
      }
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (lockedMessage != null) {
          Text(
            text = lockedMessage,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
          )
          Spacer(modifier = Modifier.height(12.dp))
        }
        val buttonHeight = ButtonDefaults.MediumContainerHeight
        Button(
          onClick = onPlay,
          enabled = isPlayEnabled,
          shapes = ButtonDefaults.shapes(),
          contentPadding = ButtonDefaults.contentPaddingFor(buttonHeight),
          modifier = Modifier.heightIn(min = buttonHeight),
        ) {
          Icon(
            imageVector = playIcon,
            contentDescription = null,
            modifier = Modifier.size(ButtonDefaults.iconSizeFor(buttonHeight)),
          )
          Spacer(modifier = Modifier.size(ButtonDefaults.IconSpacing))
          Text(text = playLabel, style = ButtonDefaults.textStyleFor(buttonHeight))
        }
      }
    }
    // Drawn last so it stays on top of the scrolling content.
    Row(
      modifier = Modifier.fillMaxWidth().height(BACK_ROW_HEIGHT).padding(horizontal = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      FilledTonalIconButton(onClick = onBack, shapes = IconButtonDefaults.shapes()) {
        Icon(
          imageVector = Icons.AutoMirrored.Filled.ArrowBack,
          contentDescription = stringResource(R.string.xrteacher_game_back),
        )
      }
    }
  }
}

/** The height of the row with the Back button at the top of a start screen. */
private val BACK_ROW_HEIGHT = 64.dp

/**
 * The "How to play" block of a start screen: a centered heading, then one numbered line per step.
 * Text in `<b>` tags is shown in bold.
 */
@Composable
private fun VocabGameHowToPlay(steps: List<String>) {
  Column(
    modifier = Modifier.widthIn(max = 330.dp).padding(horizontal = 16.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Text(
      text = stringResource(R.string.xrteacher_game_how_to_play),
      style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp),
      fontWeight = FontWeight.Bold,
      color = MaterialTheme.colorScheme.primary,
      textAlign = TextAlign.Center,
      modifier = Modifier.semantics { heading() },
    )
    for ((index, step) in steps.withIndex()) {
      Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
          contentAlignment = Alignment.Center,
          modifier =
            Modifier.size(20.dp)
              .clip(CircleShape)
              .background(MaterialTheme.colorScheme.primaryContainer),
        ) {
          Text(
            text = (index + 1).toString(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
          )
        }
        Text(
          text = AnnotatedString.fromHtml(step),
          style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
          color = MaterialTheme.colorScheme.onSurface,
          modifier = Modifier.weight(1f),
        )
      }
    }
  }
}

/**
 * One decorative shape behind a start screen.
 *
 * @param x the left edge, in dp on the 412dp-wide design frame; scaled to the screen width.
 * @param y the top edge, in dp on the design frame.
 * @param width the width, in dp on the design frame.
 * @param height the height, in dp on the design frame.
 * @param color the fill, before the shared layer alpha.
 */
private data class VocabGameBackgroundShape(
  val shape: Shape,
  val color: Color,
  val x: Float,
  val y: Float,
  val width: Float,
  val height: Float = width,
)

/** The width of the design frame the background shape positions are measured on. */
private const val DESIGN_FRAME_WIDTH = 412f

/** How opaque the whole background shapes layer is in light mode, so the shapes stay soft. */
private const val BACKGROUND_SHAPES_ALPHA_LIGHT = 0.33f

/** How opaque the whole background shapes layer is in dark mode, where soft shapes vanish. */
private const val BACKGROUND_SHAPES_ALPHA_DARK = 0.7f

/**
 * The soft shapes behind a start screen, faded out towards the bottom so they never compete with
 * the settings and the Play button. Each shape pops in on a bouncy spring when the screen opens,
 * then keeps bobbing and tilting on soft springs, so it overshoots and settles like jelly. The
 * layer has no click handling, so touches reach the content.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun VocabGameStartBackground(screenWidth: Dp, fadeColor: Color) {
  // toShape() remembers each shape itself, so the list needs no remember of its own.
  val shapes =
    listOf(
      VocabGameBackgroundShape(
        MaterialShapes.Slanted.toShape(),
        Color(0x6634A853),
        x = -23f,
        y = 167f,
        width = 126f,
      ),
      VocabGameBackgroundShape(
        MaterialShapes.Circle.toShape(),
        Color(0x66FCBD00),
        x = 318f,
        y = 159f,
        width = 82f,
      ),
      VocabGameBackgroundShape(
        MaterialShapes.Square.toShape(),
        Color(0x664285F4),
        x = -51f,
        y = 377f,
        width = 106f,
      ),
      VocabGameBackgroundShape(
        MaterialShapes.Circle.toShape(),
        Color(0x66FCBD00),
        x = 262f,
        y = 392f,
        width = 82f,
      ),
      VocabGameBackgroundShape(
        MaterialShapes.Cookie6Sided.toShape(),
        Color(0x66EA4335),
        x = 250f,
        y = 433f,
        width = 77f,
        height = 86f,
      ),
      VocabGameBackgroundShape(
        MaterialShapes.Square.toShape(),
        Color(0x6634A853),
        x = 260f,
        y = 599f,
        width = 187f,
      ),
    )
  val scale = screenWidth.value / DESIGN_FRAME_WIDTH
  val isDark = fadeColor.luminance() < 0.5f
  val layerAlpha = if (isDark) BACKGROUND_SHAPES_ALPHA_DARK else BACKGROUND_SHAPES_ALPHA_LIGHT
  Box(modifier = Modifier.fillMaxSize().alpha(layerAlpha)) {
    for ((index, item) in shapes.withIndex()) {
      val entrance = remember { Animatable(0f) }
      // Swings between -1 and 1; the soft spring overshoots each swing a little.
      val bob = remember { Animatable(0f) }
      LaunchedEffect(Unit) {
        // Staggers the shapes so they pop in one after another.
        delay(index * ENTRANCE_STAGGER_MILLIS)
        launch { entrance.animateTo(1f, spring(dampingRatio = 0.35f, stiffness = 150f)) }
        // Neighbors swing in opposite directions, with slightly different tempos.
        var target = if (index % 2 == 0) 1f else -1f
        while (true) {
          bob.animateTo(target, spring(dampingRatio = 0.3f, stiffness = Spring.StiffnessVeryLow))
          delay(BOB_PAUSE_MILLIS + index * 150L)
          target = -target
        }
      }
      Box(
        modifier =
          Modifier.offset(x = (item.x * scale).dp, y = item.y.dp)
            .size(width = (item.width * scale).dp, height = (item.height * scale).dp)
            .graphicsLayer {
              // Read in the layer block, so the animations redraw without recomposing.
              val swing = bob.value
              scaleX = entrance.value * (1f + 0.05f * swing)
              scaleY = entrance.value * (1f - 0.05f * swing)
              translationY = swing * 12.dp.toPx()
              rotationZ = swing * 12f
            }
            .clip(item.shape)
            .background(item.color)
      )
    }
  }
  // Fades the shapes out from just below the Back button to about two thirds down the screen.
  Box(
    modifier =
      Modifier.fillMaxSize()
        .padding(top = 104.dp)
        .background(Brush.verticalGradient(0f to Color.Transparent, 0.6875f to fadeColor))
  )
}

/** How long a background shape rests between two swings. */
private const val BOB_PAUSE_MILLIS = 600L

/** The delay between the entrances of consecutive background shapes. */
private const val ENTRANCE_STAGGER_MILLIS = 90L

/** The language setting of a game start screen: a menu of the [languages] with saved words. */
@Composable
fun VocabGameLanguageDropdown(
  languages: List<Language>,
  selected: Language?,
  onSelect: (Language) -> Unit,
  isLanguageEnabled: (Language) -> Boolean = { true },
) {
  VocabGameDropdown(
    label = stringResource(R.string.xrteacher_game_setting_language),
    options = languages,
    selected = selected,
    optionLabel = { getLanguageName(it) },
    placeholder = stringResource(R.string.xrteacher_study_select_language),
    onSelect = onSelect,
    isOptionEnabled = isLanguageEnabled,
    labelTrailingContent = {
      InfoTooltipIconButton(
        tooltipText = stringResource(R.string.xrteacher_game_language_info),
        contentDescription = stringResource(R.string.cd_xrteacher_game_language_info),
      )
    },
  )
}

/**
 * A game setting on a start screen: a small [label] above a white pill with the [optionLabel] of
 * the [selected] option, or [placeholder], that opens a menu of [options]. The selected option is
 * filled in the secondary container color and checked. Options where [isOptionEnabled] is `false`
 * are dimmed and not selectable. The pill is disabled while there are no options.
 */
@Composable
fun <T> VocabGameDropdown(
  label: String,
  options: List<T>,
  selected: T?,
  optionLabel: @Composable (T) -> String,
  placeholder: String,
  onSelect: (T) -> Unit,
  isOptionEnabled: (T) -> Boolean = { true },
  labelTrailingContent: (@Composable () -> Unit)? = null,
) {
  var isExpanded by remember { mutableStateOf(false) }
  val isEnabled = options.isNotEmpty()
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
      )
      labelTrailingContent?.invoke()
    }
    Spacer(modifier = Modifier.height(4.dp))
    // The Box anchors the menu to the pill.
    Box {
      Row(
        modifier =
          Modifier.height(48.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .clickable(enabled = isEnabled, role = Role.DropdownList) { isExpanded = true }
            .padding(start = 20.dp, end = 12.dp)
            .alpha(if (isEnabled) 1f else 0.38f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        Text(
          text = if (selected != null) optionLabel(selected) else placeholder,
          style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp, lineHeight = 24.sp),
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 1,
        )
        Icon(
          imageVector = Icons.Filled.ArrowDropDown,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onSurface,
          modifier = Modifier.size(24.dp),
        )
      }
      DropdownMenu(
        expanded = isExpanded,
        onDismissRequest = { isExpanded = false },
        shape = RoundedCornerShape(20.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
      ) {
        Column(
          modifier = Modifier.padding(horizontal = 4.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          for (option in options) {
            val optionEnabled = isOptionEnabled(option)
            VocabGameMenuItem(
              text = optionLabel(option),
              isSelected = option == selected,
              enabled = optionEnabled,
              onClick = {
                if (optionEnabled) {
                  isExpanded = false
                  onSelect(option)
                }
              },
            )
          }
        }
      }
    }
  }
}

/** One row of a [VocabGameDropdown] menu: a pill that is filled and checked when selected. */
@Composable
private fun VocabGameMenuItem(
  text: String,
  isSelected: Boolean,
  onClick: () -> Unit,
  enabled: Boolean = true,
) {
  val colors = MaterialTheme.colorScheme
  val contentColor = if (isSelected) colors.onSecondaryContainer else colors.onSurface
  Row(
    modifier =
      Modifier.fillMaxWidth()
        .height(40.dp)
        .clip(CircleShape)
        .background(if (isSelected) colors.secondaryContainer else Color.Transparent)
        .selectable(selected = isSelected, enabled = enabled, onClick = onClick)
        .padding(horizontal = 16.dp)
        .alpha(if (enabled) 1f else 0.38f),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Text(
      text = text,
      style = MaterialTheme.typography.labelLarge,
      color = contentColor,
      maxLines = 1,
      modifier = Modifier.weight(1f),
    )
    // Every row keeps room for the check, so the menu width does not depend on the selection.
    Box(modifier = Modifier.size(18.dp)) {
      if (isSelected) {
        Icon(
          imageVector = Icons.Filled.Check,
          contentDescription = null,
          tint = contentColor,
          modifier = Modifier.matchParentSize(),
        )
      }
    }
  }
}
