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

import androidx.annotation.VisibleForTesting
import kotlin.math.expm1
import kotlin.math.ln1p
import kotlin.math.roundToInt

/** Converts a photo [count] in `[0, maxCount]` to a logarithmic slider position in `[0f, 1f]`. */
@VisibleForTesting
internal fun countToLogSliderPosition(count: Int, maxCount: Int): Float {
  if (maxCount <= 0 || count <= 0) return 0f
  return (ln1p(count.coerceAtMost(maxCount).toDouble()) / ln1p(maxCount.toDouble())).toFloat()
}

/**
 * Converts a logarithmic slider [position] in `[0f, 1f]` back to an integer photo count in `[0,
 * maxCount]`.
 */
@VisibleForTesting
internal fun logSliderPositionToCount(position: Float, maxCount: Int): Int {
  if (maxCount <= 0 || position <= 0f) return 0
  return expm1(position.coerceIn(0f, 1f).toDouble() * ln1p(maxCount.toDouble()))
    .roundToInt()
    .coerceIn(0, maxCount)
}
