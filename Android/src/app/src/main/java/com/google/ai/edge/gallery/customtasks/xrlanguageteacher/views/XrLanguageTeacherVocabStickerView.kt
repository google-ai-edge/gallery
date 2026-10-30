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

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.XrLanguageTeacherStickerImages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Size of the pencil button that opens the cutout editor from a vocabulary card's sticker. */
internal val EDIT_CUTOUT_BUTTON_SIZE = 32.dp

/** How far the pencil button overhangs the bottom-right corner of the thumbnail it edits. */
internal val EDIT_CUTOUT_BUTTON_OVERHANG = 6.dp

/** Returns whether [image] is a segmented sticker cutout (it has transparent corners). */
@Composable
internal fun rememberIsStickerCutout(image: ImageBitmap): Boolean =
  remember(image) { XrLanguageTeacherStickerImages.hasTransparentEdge(image) }

/**
 * Renders a vocabulary photo as a tilted die-cut sticker.
 *
 * When [image] has a transparent background (a segmented cutout), draws it with the white
 * contour-following outline that [XrLanguageTeacherStickerImages.prepareStickerImage] bakes (the
 * same one the study games draw), with a soft shadow. When [image] is an opaque rectangular photo
 * fallback, frames it inside a rounded rectangle of [cornerRadius] with a white border
 * [outlineWidth] wide.
 */
@Composable
internal fun XrLanguageTeacherVocabStickerView(
  image: ImageBitmap,
  contentDescription: String?,
  modifier: Modifier = Modifier,
  outlineWidth: Dp = 4.dp,
  tiltDegrees: Float = -6f,
  cornerRadius: Dp = 14.dp,
) {
  val isCutout = rememberIsStickerCutout(image)
  val semanticsModifier =
    if (contentDescription != null) {
      Modifier.clearAndSetSemantics { this.contentDescription = contentDescription }
    } else {
      Modifier.clearAndSetSemantics {}
    }
  if (isCutout) {
    // Baked off the main thread at the image's own size, so the outlined sticker stays as sharp as
    // the cutout without blocking composition.
    val androidBitmap = remember(image) { image.asAndroidBitmap() }
    var outlinedSticker by
      remember(image) {
        mutableStateOf(XrLanguageTeacherStickerImages.getCachedOutlinedSticker(androidBitmap))
      }
    if (outlinedSticker == null) {
      LaunchedEffect(image) {
        outlinedSticker =
          withContext(Dispatchers.Default) {
            XrLanguageTeacherStickerImages.getOrPrepareOutlinedSticker(androidBitmap)
          }
      }
    }
    val sticker = outlinedSticker ?: image
    Box(
      modifier = modifier.then(semanticsModifier).rotate(tiltDegrees),
      contentAlignment = Alignment.Center,
    ) {
      // Soft shadow under the outlined sticker.
      Image(
        bitmap = sticker,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        colorFilter = ColorFilter.tint(Color.Black.copy(alpha = 0.18f), BlendMode.SrcIn),
        modifier = Modifier.matchParentSize().offset(y = 3.dp),
      )
      Image(
        bitmap = sticker,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        // Fills the available space; the shadow layer matches its size.
        modifier = Modifier.fillMaxSize(),
      )
    }
  } else {
    val shape = RoundedCornerShape(cornerRadius)
    Image(
      bitmap = image,
      contentDescription = null,
      contentScale = ContentScale.Crop,
      modifier =
        modifier
          .then(semanticsModifier)
          .rotate(tiltDegrees)
          .shadow(elevation = 5.dp, shape = shape)
          .clip(shape)
          .border(width = outlineWidth, color = Color.White, shape = shape),
    )
  }
}

/**
 * A vocabulary card thumbnail: [XrLanguageTeacherVocabStickerView] for a sticker cutout, or the
 * photo center-cropped into a rounded square of [cornerRadius] otherwise.
 */
@Composable
internal fun VocabThumbnailImage(
  image: ImageBitmap,
  contentDescription: String?,
  modifier: Modifier = Modifier,
  cornerRadius: Dp = 16.dp,
) {
  if (rememberIsStickerCutout(image)) {
    XrLanguageTeacherVocabStickerView(
      image = image,
      contentDescription = contentDescription,
      outlineWidth = 3.5.dp,
      tiltDegrees = -6f,
      cornerRadius = cornerRadius,
      modifier = modifier,
    )
  } else {
    Image(
      bitmap = image,
      contentDescription = contentDescription,
      contentScale = ContentScale.Crop,
      modifier = modifier.clip(RoundedCornerShape(cornerRadius)),
    )
  }
}

/**
 * The small white circular pencil button, with a subtle shadow, that overlaps the bottom-right
 * corner of a vocabulary card's sticker and opens the cutout editor.
 *
 * While [isDimmed] (the sticker segmentation model isn't downloaded yet), it is drawn greyed out
 * but stays clickable, so that tapping it can offer the download.
 */
@Composable
internal fun EditCutoutButton(
  onClick: () -> Unit,
  contentDescription: String,
  modifier: Modifier = Modifier,
  isDimmed: Boolean = false,
) {
  Surface(
    onClick = onClick,
    shape = CircleShape,
    // White in both themes, like the sticker outline it overlaps.
    color = if (isDimmed) MaterialTheme.colorScheme.surfaceContainerHighest else Color.White,
    shadowElevation = if (isDimmed) 1.dp else 3.dp,
    modifier = modifier.size(EDIT_CUTOUT_BUTTON_SIZE),
  ) {
    Box(contentAlignment = Alignment.Center) {
      Icon(
        imageVector = Icons.Rounded.Edit,
        contentDescription = contentDescription,
        tint =
          if (isDimmed) {
            MaterialTheme.colorScheme.onSurface.copy(alpha = DIMMED_EDIT_CUTOUT_ICON_ALPHA)
          } else {
            MaterialTheme.colorScheme.primary
          },
        modifier = Modifier.size(18.dp),
      )
    }
  }
}

/** The pencil icon's opacity while [EditCutoutButton] is dimmed, as for a disabled control. */
private const val DIMMED_EDIT_CUTOUT_ICON_ALPHA = 0.38f
