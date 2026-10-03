package com.google.ai.edge.gallery.ui.common.modelitem

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.ui.Modifier

@ExperimentalSharedTransitionApi
fun Modifier.sharedElement(
  sharedContentState: SharedTransitionScope.SharedContentState,
  animatedVisibilityScope: AnimatedVisibilityScope
): Modifier = this
