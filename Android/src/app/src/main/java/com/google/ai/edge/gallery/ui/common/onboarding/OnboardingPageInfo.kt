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

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.painter.Painter

/**
 * Data structure holding information for an individual onboarding page.
 *
 * @property titleRes Optional resource ID of the page title string.
 * @property descriptionRes Optional resource ID of the page description string (supports Markdown).
 * @property imageRes Optional drawable resource ID of the page image (e.g. in drawable-nodpi). 0 if
 *   no image.
 * @property title In-memory title string (used when [titleRes] is 0).
 * @property description In-memory description markdown string (used when [descriptionRes] is 0).
 * @property imagePainter Optional in-memory [Painter] (e.g. `ColorPainter` for testing or
 *   previews).
 */
data class OnboardingPageInfo(
  @StringRes val titleRes: Int = 0,
  @StringRes val descriptionRes: Int = 0,
  @DrawableRes val imageRes: Int = 0,
  val title: String = "",
  val description: String = "",
  val imagePainter: Painter? = null,
)
