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

package com.google.ai.edge.gallery.ui.common.modelitem

import androidx.compose.animation.core.DeferredTargetAnimation
import androidx.compose.animation.core.ExperimentalAnimatableApi
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.layout.approachLayout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.round

const val LAYOUT_ANIMATION_DURATION = 250

context(LookaheadScope)
@OptIn(ExperimentalAnimatableApi::class)
fun Modifier.animateLayout(): Modifier = composed {
  val sizeAnim = remember { DeferredTargetAnimation(IntSize.VectorConverter) }
  val offsetAnim = remember { DeferredTargetAnimation(IntOffset.VectorConverter) }
  val scope = rememberCoroutineScope()

  this.approachLayout(
    isMeasurementApproachInProgress = { lookaheadSize ->
      sizeAnim.updateTarget(lookaheadSize, scope, tween(LAYOUT_ANIMATION_DURATION))
      !sizeAnim.isIdle
    },
    isPlacementApproachInProgress = { lookaheadCoordinates ->
      val target = lookaheadScopeCoordinates.localLookaheadPositionOf(lookaheadCoordinates)
      offsetAnim.updateTarget(target.round(), scope, tween(LAYOUT_ANIMATION_DURATION))
      !offsetAnim.isIdle
    }
  ) { measurable, _ ->
    val (animWidth, animHeight) = sizeAnim.updateTarget(
      lookaheadSize,
      scope,
      tween(LAYOUT_ANIMATION_DURATION)
    )
    measurable.measure(Constraints.fixed(animWidth, animHeight))
      .run {
        layout(width, height) {
          coordinates?.let {
            val target = lookaheadScopeCoordinates.localLookaheadPositionOf(it).round()
            val animOffset = offsetAnim.updateTarget(target, scope, tween(LAYOUT_ANIMATION_DURATION))
            val current = lookaheadScopeCoordinates.localPositionOf(it, Offset.Zero).round()
            val (x, y) = animOffset - current
            place(x, y)
          } ?: place(0, 0)
        }
      }
  }
}