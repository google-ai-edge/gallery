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

package com.google.ai.edge.gallery.ui.common.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.ui.common.MarkdownText
import kotlinx.coroutines.launch

/**
 * A self-contained onboarding dialog that checks whether the onboarding for [featureId] has already
 * been visited in persistent DataStore.
 *
 * If already visited, renders an empty composable unless [forceShow] is true. When the user
 * completes the final step or dismisses the dialog, the feature is recorded as visited.
 *
 * @param featureId Unique identifier for this feature onboarding.
 * @param pages List of [OnboardingPageInfo] describing each slide.
 * @param modifier Optional modifier for the dialog card.
 * @param onDismiss Callback invoked when the dialog is dismissed or completed.
 * @param forceShow If true, forces the dialog to display even if previously visited.
 * @param viewModel [OnboardingViewModel] used to check and record visited state.
 */
@Composable
fun OnboardingDialog(
  featureId: String,
  pages: List<OnboardingPageInfo>,
  modifier: Modifier = Modifier,
  onDismiss: () -> Unit = {},
  forceShow: Boolean = false,
  viewModel: OnboardingViewModel = hiltViewModel(),
) {
  if (pages.isEmpty()) {
    return
  }

  val isVisited by viewModel.isVisitedFlow(featureId).collectAsState(initial = null)

  // While loading persistent state, or if already visited, do not show dialog.
  if (isVisited == null || (isVisited == true && !forceShow)) {
    return
  }

  OnboardingDialogContent(
    pages = pages,
    modifier = modifier,
    onDismiss = {
      viewModel.markVisited(featureId)
      onDismiss()
    },
  )
}

/**
 * Pure presentational overload for [OnboardingDialog] without internal DataStore check.
 *
 * @param pages List of [OnboardingPageInfo] describing each slide.
 * @param onDismiss Callback invoked when the dialog is completed or dismissed.
 * @param modifier Optional modifier for the dialog card.
 * @param initialPage Initial page index (default 0).
 */
@Composable
fun OnboardingDialog(
  pages: List<OnboardingPageInfo>,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
  initialPage: Int = 0,
) {
  OnboardingDialogContent(
    pages = pages,
    onDismiss = onDismiss,
    modifier = modifier,
    initialPage = initialPage,
  )
}

/** Content composable for rendering the multi-page onboarding dialog. */
@Composable
fun OnboardingDialogContent(
  pages: List<OnboardingPageInfo>,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
  initialPage: Int = 0,
) {
  if (pages.isEmpty()) {
    return
  }

  val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { pages.size })
  val coroutineScope = rememberCoroutineScope()

  BackHandler(enabled = pagerState.currentPage > 0) {
    coroutineScope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
  }

  Dialog(
    onDismissRequest = { /* Modal dialog: not dismissable on backdrop tap */ },
    properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
  ) {
    Card(
      shape = RoundedCornerShape(28.dp),
      modifier = modifier.fillMaxWidth().fillMaxHeight(0.8f),
      colors =
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
      Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        // Top animated page indicator.
        if (pages.size > 1) {
          OnboardingPageIndicator(
            pageCount = pages.size,
            currentPage = pagerState.currentPage,
            modifier = Modifier.padding(bottom = 16.dp),
          )
        }

        // Horizontal Pager for onboarding pages.
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxWidth().weight(1f)) {
          pageIndex ->
          OnboardingPage(page = pages[pageIndex], modifier = Modifier.fillMaxSize())
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Bottom navigation buttons.
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
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
          } else {
            Spacer(modifier = Modifier.width(1.dp))
          }

          val isLastPage = pagerState.currentPage == pages.size - 1
          Button(
            onClick = {
              if (isLastPage) {
                onDismiss()
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
              text = stringResource(if (isLastPage) R.string.done else R.string.next),
              fontWeight = FontWeight.Medium,
            )
          }
        }
      }
    }
  }
}

/**
 * Animated page indicator row where the active page indicator animates to a pill shape and inactive
 * indicators animate to dots.
 */
@Composable
fun OnboardingPageIndicator(pageCount: Int, currentPage: Int, modifier: Modifier = Modifier) {
  Row(
    modifier = modifier,
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    for (i in 0 until pageCount) {
      val isSelected = i == currentPage
      val width by
        animateDpAsState(
          targetValue = if (isSelected) 24.dp else 8.dp,
          animationSpec =
            spring(
              dampingRatio = Spring.DampingRatioMediumBouncy,
              stiffness = Spring.StiffnessMediumLow,
            ),
          label = "indicator_width_$i",
        )
      val color by
        animateColorAsState(
          targetValue =
            if (isSelected) {
              MaterialTheme.colorScheme.primary
            } else {
              MaterialTheme.colorScheme.primary.copy(alpha = 0.38f)
            },
          animationSpec =
            spring(
              dampingRatio = Spring.DampingRatioNoBouncy,
              stiffness = Spring.StiffnessMediumLow,
            ),
          label = "indicator_color_$i",
        )
      Box(modifier = Modifier.height(8.dp).width(width).clip(CircleShape).background(color))
    }
  }
}

/**
 * Single onboarding page displaying title, picture (resource or painter), and markdown description
 * text.
 */
@Composable
fun OnboardingPage(page: OnboardingPageInfo, modifier: Modifier = Modifier) {
  val displayTitle = if (page.titleRes != 0) stringResource(page.titleRes) else page.title
  val displayDescription =
    if (page.descriptionRes != 0) stringResource(page.descriptionRes) else page.description

  Column(modifier = modifier) {
    // Title
    if (displayTitle.isNotEmpty()) {
      Text(
        text = displayTitle,
        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Medium),
        color = MaterialTheme.colorScheme.onSurface,
      )
      Spacer(modifier = Modifier.height(16.dp))
    }

    // Picture from drawable resource or in-memory painter
    if (page.imagePainter != null) {
      Image(
        painter = page.imagePainter,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(20.dp)),
      )
      Spacer(modifier = Modifier.height(16.dp))
    } else if (page.imageRes != 0) {
      Image(
        painter = painterResource(page.imageRes),
        contentDescription = null,
        contentScale = ContentScale.FillWidth,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)),
      )
      Spacer(modifier = Modifier.height(16.dp))
    }

    // Paragraph description
    if (displayDescription.isNotEmpty()) {
      Column(modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
        MarkdownText(
          text = displayDescription,
          textColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    } else {
      Spacer(modifier = Modifier.weight(1f))
    }
  }
}
