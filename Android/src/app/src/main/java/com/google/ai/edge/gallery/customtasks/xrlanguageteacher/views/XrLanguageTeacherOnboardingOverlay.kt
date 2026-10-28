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

import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.view.View
import android.widget.ImageView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.CharacterPersona
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.ONBOARDING_PAGE_COUNT
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.ONBOARDING_PREFERENCES_PAGE
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.PersonaPreviewState
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.TeacherLanguagePackUiState
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.TeacherLanguageRole
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.TeacherPreferencesDraft
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.XLT_ONBOARDING_CARDS
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.rememberTeacherPreferencesDraft
import com.google.ai.edge.gallery.proto.Language
import com.google.ai.edge.gallery.ui.common.onboarding.OnboardingPageIndicator
import com.google.ai.edge.gallery.ui.common.onboarding.OnboardingPageInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Non-dismissable, swipeable introduction shown the first time the user opens XR Language Teacher:
 * one page per card of [XLT_ONBOARDING_CARDS] (welcome, point-tap-ask, and reviewing saved words),
 * then a preferences page with [TeacherPreferencesForm]. "Get started" calls [onGetStarted] with
 * the chosen preferences. [languagePacks] and [onDownloadLanguagePack] drive the form's language
 * pack download controls.
 *
 * Tapping outside does not close it. Back goes to the previous page, or calls [onExit] on the first
 * page so that the user can still leave the feature.
 */
@Composable
fun XrLanguageTeacherOnboardingOverlay(
  initialPreferences: TeacherPreferencesDraft,
  availableLanguages: List<Language>,
  availableNativeLanguages: List<Language> = availableLanguages,
  getPersonaPromptTemplate: (CharacterPersona) -> String,
  onPlayPersonaPreview: (CharacterPersona, Language) -> Unit,
  onGetStarted: (TeacherPreferencesDraft) -> Unit,
  onExit: () -> Unit,
  personaPreviewState: PersonaPreviewState = PersonaPreviewState.Idle,
  isPersonaPreviewAvailable: Boolean = true,
  onStopPersonaPreview: () -> Unit = {},
  languagePacks: TeacherLanguagePackUiState = TeacherLanguagePackUiState(),
  onDownloadLanguagePack: (TeacherLanguageRole, Language) -> Unit = { _, _ -> },
) {
  var draft by rememberTeacherPreferencesDraft(initialPreferences)
  val pagerState = rememberPagerState(pageCount = { ONBOARDING_PAGE_COUNT })
  val coroutineScope = rememberCoroutineScope()
  DisposableEffect(Unit) { onDispose { onStopPersonaPreview() } }

  Dialog(
    onDismissRequest = { /* Not dismissable: back is handled below. */ },
    properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
  ) {
    // Registered inside the dialog so that it receives back presses from the dialog window.
    BackHandler {
      if (pagerState.currentPage > 0) {
        coroutineScope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
      } else {
        onExit()
      }
    }
    Card(
      shape = RoundedCornerShape(28.dp),
      modifier = Modifier.fillMaxWidth().fillMaxHeight(0.85f),
      colors =
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
      Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        OnboardingPageIndicator(
          pageCount = ONBOARDING_PAGE_COUNT,
          currentPage = pagerState.currentPage,
          modifier = Modifier.padding(bottom = 16.dp),
        )

        HorizontalPager(
          state = pagerState,
          pageSpacing = 24.dp,
          verticalAlignment = Alignment.Top,
          modifier = Modifier.fillMaxWidth().weight(1f),
        ) { page ->
          val card = XLT_ONBOARDING_CARDS.getOrNull(page)
          if (card != null) {
            OnboardingIntroPage(card = card, isCurrentPage = pagerState.currentPage == page)
          } else {
            Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
              DialogTitle(text = stringResource(R.string.xrteacher_onboarding_preferences_title))
              Spacer(modifier = Modifier.height(20.dp))
              TeacherPreferencesForm(
                draft = draft,
                availableLanguages = availableLanguages,
                availableNativeLanguages = availableNativeLanguages,
                onDraftChange = { draft = it },
                getPersonaPromptTemplate = getPersonaPromptTemplate,
                onPlayPersonaPreview = onPlayPersonaPreview,
                personaPreviewState = personaPreviewState,
                isPersonaPreviewAvailable = isPersonaPreviewAvailable,
                languagePacks = languagePacks,
                onDownloadLanguagePack = onDownloadLanguagePack,
              )
            }
          }
        }

        Spacer(modifier = Modifier.height(16.dp))

        val isLastPage = pagerState.currentPage == ONBOARDING_PREFERENCES_PAGE
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          if (pagerState.currentPage > 0) {
            TextButton(
              onClick = {
                coroutineScope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
              }
            ) {
              Text(text = stringResource(R.string.previous), fontWeight = FontWeight.Medium)
            }
          }
          Button(
            onClick = {
              if (isLastPage) {
                onStopPersonaPreview()
                onGetStarted(draft)
              } else {
                coroutineScope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
              }
            },
            shape = RoundedCornerShape(24.dp),
          ) {
            Icon(
              imageVector =
                if (isLastPage) Icons.Default.Check else Icons.AutoMirrored.Filled.ArrowForward,
              contentDescription = null,
              modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
              text =
                stringResource(if (isLastPage) R.string.xrteacher_get_started else R.string.next),
              fontWeight = FontWeight.Medium,
            )
          }
        }
      }
    }
  }
}

/**
 * A first-time introduction page: the [card]'s title, its looping animation (else its image, else a
 * placeholder), and body text. Uses [OnboardingPageInfo.title] and [OnboardingPageInfo.description]
 * when the card has no string resources.
 *
 * @param isCurrentPage whether this page is the one on screen; the animation only plays then.
 */
@Composable
private fun OnboardingIntroPage(card: OnboardingPageInfo, isCurrentPage: Boolean) {
  val title = if (card.titleRes != 0) stringResource(card.titleRes) else card.title
  val description =
    if (card.descriptionRes != 0) stringResource(card.descriptionRes) else card.description
  val imagePainter =
    card.imagePainter ?: if (card.imageRes != 0) painterResource(card.imageRes) else null
  val assetImage = rememberAssetImageBitmap(card.imageAssetPath)
  val hasAnimation = card.animationRes != 0 || card.animationAssetPath.isNotEmpty()
  Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
    DialogTitle(text = title)
    Spacer(modifier = Modifier.height(20.dp))
    // The animations and screenshots are portrait phone screens, so the whole screen is shown
    // centered on a tonal panel instead of being cropped. Behind an animation or image, the panel
    // has a subtle Gemini-style gradient: the Gemini blue, violet and rose, each mixed mostly with
    // primaryContainer so it works in light and dark themes.
    val panelColor = MaterialTheme.colorScheme.primaryContainer
    val panelBackground =
      if (hasAnimation || imagePainter != null) {
        Modifier.background(
          Brush.linearGradient(
            0.0f to lerp(panelColor, GEMINI_BLUE, GEMINI_BLUE_MIX),
            0.55f to lerp(panelColor, GEMINI_VIOLET, GEMINI_VIOLET_MIX),
            1.0f to lerp(panelColor, GEMINI_ROSE, GEMINI_ROSE_MIX),
          )
        )
      } else {
        Modifier.background(panelColor)
      }
    Box(
      modifier =
        Modifier.fillMaxWidth()
          .height(ONBOARDING_MEDIA_HEIGHT)
          .clip(RoundedCornerShape(24.dp))
          .then(panelBackground)
          // An animation fills the panel's height: its own transparent margins frame the phone,
          // which grows past them, up to the panel's edge, when it zooms in. Asset images bring
          // their own background and fill the whole panel.
          .padding(
            if (hasAnimation || card.imageAssetPath.isNotEmpty()) 0.dp else ONBOARDING_MEDIA_PADDING
          ),
      contentAlignment = Alignment.Center,
    ) {
      when {
        hasAnimation ->
          OnboardingAnimation(
            animationAssetPath = card.animationAssetPath,
            animationRes = card.animationRes,
            isPlaying = isCurrentPage,
          )
        assetImage != null ->
          Image(
            bitmap = assetImage,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
          )
        imagePainter != null ->
          Image(
            painter = imagePainter,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
          )
      // Without media, the empty panel is a placeholder matching the design mock.
      }
    }
    Spacer(modifier = Modifier.height(24.dp))
    Text(
      text = description,
      style = MaterialTheme.typography.bodyLarge,
      color = MaterialTheme.colorScheme.onSurface,
    )
  }
}

/**
 * Decodes the image at [assetPath] in the app's assets off the main thread. Returns `null` while it
 * loads, if [assetPath] is empty, or if it can't be decoded.
 */
@Composable
private fun rememberAssetImageBitmap(assetPath: String): ImageBitmap? {
  val assets = LocalContext.current.assets
  val bitmap by
    produceState<ImageBitmap?>(initialValue = null, assetPath) {
      if (assetPath.isEmpty()) return@produceState
      value =
        withContext(Dispatchers.IO) {
          runCatching { assets.open(assetPath).use { BitmapFactory.decodeStream(it) } }
            .getOrNull()
            ?.asImageBitmap()
        }
    }
  return bitmap
}

/**
 * Plays the looping animated image (an animated WebP) at [animationAssetPath] in the app's assets,
 * or else in raw resource [animationRes], as large as fits at its own aspect ratio, while
 * [isPlaying]; it pauses on the current frame otherwise.
 *
 * The animation brings its own look: the phone screen with rounded corners on a transparent canvas,
 * growing and shrinking as it zooms, so the panel's gradient shows around it.
 *
 * The platform's [AnimatedImageDrawable] decodes frames on a background thread as they are drawn,
 * so a long animation never needs all its frames in memory.
 */
@Composable
private fun OnboardingAnimation(
  animationAssetPath: String,
  animationRes: Int,
  isPlaying: Boolean,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val animation by
    produceState<AnimatedImageDrawable?>(initialValue = null, animationAssetPath, animationRes) {
      value =
        withContext(Dispatchers.IO) {
          val source =
            if (animationAssetPath.isNotEmpty()) {
              ImageDecoder.createSource(context.assets, animationAssetPath)
            } else {
              ImageDecoder.createSource(context.resources, animationRes)
            }
          runCatching { ImageDecoder.decodeDrawable(source) as? AnimatedImageDrawable }.getOrNull()
        }
    }
  val drawable = animation ?: return
  DisposableEffect(drawable, isPlaying) {
    if (isPlaying) drawable.start() else drawable.stop()
    onDispose { drawable.stop() }
  }
  val aspectRatio =
    if (drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0) {
      drawable.intrinsicWidth.toFloat() / drawable.intrinsicHeight
    } else {
      1f
    }
  AndroidView(
    factory = { context ->
      ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
      }
    },
    update = { imageView -> imageView.setImageDrawable(drawable) },
    modifier =
      modifier.fillMaxHeight().aspectRatio(ratio = aspectRatio, matchHeightConstraintsFirst = true),
  )
}

/** Height of the animation or image panel of an introduction page. */
private val ONBOARDING_MEDIA_HEIGHT = 300.dp

/** Space between the panel's edge and its image. */
private val ONBOARDING_MEDIA_PADDING = 12.dp

/** Gemini brand blue, violet and rose, the stops of the gradient behind an animation. */
private val GEMINI_BLUE = Color(0xFF4796E3)
private val GEMINI_VIOLET = Color(0xFF9177C7)
private val GEMINI_ROSE = Color(0xFFCA6673)

/**
 * How much of each Gemini color is mixed into primaryContainer for the gradient: 0 is plain
 * primaryContainer, 1 is the Gemini color. Low values keep the gradient subtle.
 */
private const val GEMINI_BLUE_MIX = 0.12f
private const val GEMINI_VIOLET_MIX = 0.28f
private const val GEMINI_ROSE_MIX = 0.22f
