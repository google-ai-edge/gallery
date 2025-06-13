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

package com.google.ai.edge.gallery.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.google.ai.edge.gallery.R

val nunitoFontFamily =
  FontFamily(
    Font(R.font.nunito_regular, FontWeight.Normal),
    Font(R.font.nunito_extralight, FontWeight.ExtraLight),
    Font(R.font.nunito_light, FontWeight.Light),
    Font(R.font.nunito_medium, FontWeight.Medium),
    Font(R.font.nunito_semibold, FontWeight.SemiBold),
    Font(R.font.nunito_bold, FontWeight.Bold),
    Font(R.font.nunito_extrabold, FontWeight.ExtraBold),
    Font(R.font.nunito_black, FontWeight.Black),
  )

val baseline = Typography()

val AppTypography =
  Typography(
    displayLarge = baseline.displayLarge.copy(fontFamily = nunitoFontFamily),
    displayMedium = baseline.displayMedium.copy(fontFamily = nunitoFontFamily),
    displaySmall = baseline.displaySmall.copy(fontFamily = nunitoFontFamily),
    headlineLarge = baseline.headlineLarge.copy(fontFamily = nunitoFontFamily),
    headlineMedium = baseline.headlineMedium.copy(fontFamily = nunitoFontFamily),
    headlineSmall = baseline.headlineSmall.copy(fontFamily = nunitoFontFamily),
    titleLarge = baseline.titleLarge.copy(fontFamily = nunitoFontFamily),
    titleMedium = baseline.titleMedium.copy(fontFamily = nunitoFontFamily),
    titleSmall = baseline.titleSmall.copy(fontFamily = nunitoFontFamily),
    bodyLarge = baseline.bodyLarge.copy(fontFamily = nunitoFontFamily),
    bodyMedium = baseline.bodyMedium.copy(fontFamily = nunitoFontFamily),
    bodySmall = baseline.bodySmall.copy(fontFamily = nunitoFontFamily),
    labelLarge = baseline.labelLarge.copy(fontFamily = nunitoFontFamily),
    labelMedium = baseline.labelMedium.copy(fontFamily = nunitoFontFamily),
    labelSmall = baseline.labelSmall.copy(fontFamily = nunitoFontFamily),
  )

val titleMediumNarrow =
  baseline.titleMedium.copy(fontFamily = nunitoFontFamily, letterSpacing = 0.0.sp)

val titleSmaller =
  baseline.titleSmall.copy(
    fontFamily = nunitoFontFamily,
    fontSize = 12.sp,
    fontWeight = FontWeight.Bold,
  )

val labelSmallNarrow =
  baseline.labelSmall.copy(fontFamily = nunitoFontFamily, letterSpacing = 0.0.sp)

val labelSmallNarrowMedium =
  baseline.labelSmall.copy(
    fontFamily = nunitoFontFamily,
    fontWeight = FontWeight.Medium,
    letterSpacing = 0.0.sp,
  )

val bodySmallNarrow = baseline.bodySmall.copy(fontFamily = nunitoFontFamily, letterSpacing = 0.0.sp)

val bodySmallSemiBold =
  baseline.bodySmall.copy(fontFamily = nunitoFontFamily, fontWeight = FontWeight.SemiBold)

val bodyMediumSemiBold =
  baseline.bodyMedium.copy(fontFamily = nunitoFontFamily, fontWeight = FontWeight.SemiBold)

val bodySmallMediumNarrow =
  baseline.bodySmall.copy(fontFamily = nunitoFontFamily, letterSpacing = 0.0.sp, fontSize = 14.sp)

val bodySmallMediumNarrowBold =
  baseline.bodySmall.copy(
    fontFamily = nunitoFontFamily,
    letterSpacing = 0.0.sp,
    fontSize = 14.sp,
    fontWeight = FontWeight.Bold,
  )
