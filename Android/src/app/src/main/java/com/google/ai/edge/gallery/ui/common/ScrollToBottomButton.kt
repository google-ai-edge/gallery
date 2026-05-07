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

package com.google.ai.edge.gallery.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.google.ai.edge.gallery.R

/**
 * A button that is animated to show or hide based on the [isAtBottom] state. When visible, clicking
 * it triggers the [onClick] action, typically used to scroll to the bottom of a view.
 */
@Composable
fun ScrollToBottomButton(isAtBottom: Boolean, onClick: () -> Unit) {
  AnimatedVisibility(
    visible = !isAtBottom,
    enter =
      fadeIn(animationSpec = tween(durationMillis = 300)) +
        scaleIn(
          animationSpec =
            spring(
              dampingRatio = Spring.DampingRatioMediumBouncy,
              stiffness = Spring.StiffnessMedium,
            )
        ),
    exit = fadeOut(animationSpec = tween(durationMillis = 200)),
  ) {
    IconButton(
      onClick = onClick,
      colors =
        IconButtonDefaults.filledIconButtonColors(
          containerColor = MaterialTheme.colorScheme.secondaryContainer
        ),
    ) {
      Icon(
        imageVector = Icons.Outlined.ArrowDownward,
        contentDescription = stringResource(R.string.cd_scroll_to_bottom),
        tint = MaterialTheme.colorScheme.onSecondaryContainer,
      )
    }
  }
}
