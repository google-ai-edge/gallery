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

package com.google.ai.edge.gallery.customtasks.smartalbum

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.ui.common.onboarding.OnboardingDialog
import com.google.ai.edge.gallery.ui.common.onboarding.OnboardingPageInfo

/** Feature identifier for Instant Media Search onboarding. */
const val SMART_ALBUM_FEATURE_ID = "smart_album"

/** Onboarding pages for Instant Media Search (Smart Album). */
val smartAlbumOnboardingPages =
  listOf(
    OnboardingPageInfo(
      titleRes = R.string.smartalbum_onboarding_welcome_title,
      descriptionRes = R.string.smartalbum_onboarding_desc_1,
      imageRes = R.drawable.smartalbum_onboarding_1,
    ),
    OnboardingPageInfo(
      titleRes = R.string.smartalbum_onboarding_title_2,
      descriptionRes = R.string.smartalbum_onboarding_desc_2,
      imageRes = R.drawable.smartalbum_onboarding_2,
    ),
  )

/**
 * Onboarding dialog for Instant Media Search (Smart Album). Displays a multi-step introduction to
 * EmbeddingGemmaV2 capabilities and usage instructions using [OnboardingDialog].
 */
@Composable
fun SmartAlbumOnboardingDialog(
  modifier: Modifier = Modifier,
  initialStep: Int? = null,
  forceShow: Boolean = false,
  onDismiss: () -> Unit = {},
) {
  if (initialStep != null) {
    OnboardingDialog(
      pages = smartAlbumOnboardingPages,
      onDismiss = onDismiss,
      modifier = modifier,
      initialPage = initialStep,
    )
  } else {
    OnboardingDialog(
      featureId = SMART_ALBUM_FEATURE_ID,
      pages = smartAlbumOnboardingPages,
      modifier = modifier,
      onDismiss = onDismiss,
      forceShow = forceShow,
    )
  }
}
