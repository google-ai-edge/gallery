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

package com.google.ai.edge.gallery.customtasks.videomomentfinder

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.ui.common.onboarding.OnboardingDialog
import com.google.ai.edge.gallery.ui.common.onboarding.OnboardingPageInfo

/** Feature identifier for Video Moment Finder onboarding. */
const val VIDEO_MOMENT_FINDER_FEATURE_ID = "video_moment_finder"

/** Onboarding pages for Video Moment Finder. */
val videoMomentFinderOnboardingPages =
  listOf(
    OnboardingPageInfo(
      titleRes = R.string.videomomentfinder_onboarding_title_1,
      descriptionRes = R.string.videomomentfinder_onboarding_desc_1,
      imageRes = R.drawable.videomomentfinder_onboarding_img1,
    ),
    OnboardingPageInfo(
      titleRes = R.string.videomomentfinder_onboarding_title_2,
      descriptionRes = R.string.videomomentfinder_onboarding_desc_2,
      imageRes = R.drawable.videomomentfinder_onboarding_img2,
    ),
    OnboardingPageInfo(
      titleRes = R.string.videomomentfinder_onboarding_title_3,
      descriptionRes = R.string.videomomentfinder_onboarding_desc_3_v2,
      imageRes = R.drawable.videomomentfinder_onboarding_img3,
    ),
  )

/**
 * Onboarding dialog for Video Moment Finder. Displays a multi-step introduction to Video Moment
 * Finder capabilities using [OnboardingDialog].
 */
@Composable
fun VideoMomentFinderOnboardingDialog(
  modifier: Modifier = Modifier,
  initialStep: Int? = null,
  forceShow: Boolean = false,
  onDismiss: () -> Unit = {},
) {
  if (initialStep != null) {
    OnboardingDialog(
      pages = videoMomentFinderOnboardingPages,
      onDismiss = onDismiss,
      modifier = modifier,
      initialPage = initialStep,
    )
  } else {
    OnboardingDialog(
      featureId = VIDEO_MOMENT_FINDER_FEATURE_ID,
      pages = videoMomentFinderOnboardingPages,
      modifier = modifier,
      onDismiss = onDismiss,
      forceShow = forceShow,
    )
  }
}
