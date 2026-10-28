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

package com.google.ai.edge.gallery.ui.common.tts

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.ai.edge.gallery.common.hasHiltComponentManager
import com.google.ai.edge.gallery.ui.common.OnAppBackgroundedEffect
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.collectLatest

/** Resolves the process-wide [TtsPlaybackController] when TTS is enabled under Hilt. */
@Composable
fun rememberChatTtsController(context: Context): TtsPlaybackController? =
  if (hasHiltComponentManager(context)) {
    hiltViewModel<TtsPlaybackViewModel>().controller.also { controller ->
      LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { controller.onReadAloudScreenResumed() }
      OnAppBackgroundedEffect { controller.onAppBackgrounded() }
    }
  } else {
    null
  }

/** Scroll and viewport modifiers coordinating spoken-sentence centering in chat message lists. */
data class ChatTtsScrollState(
  val userScrollConnection: NestedScrollConnection,
  val viewportPositionModifier: Modifier,
)

/**
 * Coordinates automatic viewport scrolling toward the active spoken sentence and pauses follow-mode
 * whenever the user manually scrolls the message list.
 */
@Composable
fun rememberChatTtsScrollState(
  ttsController: TtsPlaybackController?,
  listState: ScrollState,
  viewportHeightPx: Int,
): ChatTtsScrollState {
  var viewportTopInRootPx by remember { mutableFloatStateOf(0f) }
  val currentViewportHeightPx by rememberUpdatedState(viewportHeightPx)

  val userScrollConnection =
    remember(ttsController) {
      object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
          if (
            ttsController != null &&
              source == NestedScrollSource.UserInput &&
              available.y != 0f &&
              ttsController.activeMessageId.value != null
          ) {
            ttsController.setAutoScrollEnabled(false)
          }
          return Offset.Zero
        }
      }
    }

  val viewportPositionModifier =
    if (ttsController != null) {
      Modifier.onGloballyPositioned { viewportTopInRootPx = it.positionInRoot().y }
    } else {
      Modifier
    }

  LaunchedEffect(ttsController) {
    ttsController?.scrollRequest?.collectLatest { request ->
      val heightPx = currentViewportHeightPx
      if (request != null && heightPx > 0) {
        val viewportCenterInRootY = viewportTopInRootPx + heightPx / 2f
        val deltaPx = (request.centerYInRoot - viewportCenterInRootY).roundToInt()
        if (abs(deltaPx) > 1) {
          val targetScrollPx = (listState.value + deltaPx).coerceIn(0, listState.maxValue)
          listState.animateScrollTo(targetScrollPx)
        }
      }
    }
  }

  return remember(userScrollConnection, viewportPositionModifier) {
    ChatTtsScrollState(
      userScrollConnection = userScrollConnection,
      viewportPositionModifier = viewportPositionModifier,
    )
  }
}

/** Expandable banner hosting [TtsControlBar] above the message list whenever TTS is active. */
@Composable
fun ChatTtsControlBanner(ttsController: TtsPlaybackController?, modifier: Modifier = Modifier) {
  if (ttsController == null) return

  val isTtsExpanded by ttsController.isExpanded.collectAsStateWithLifecycle()
  val ttsPlaybackState by ttsController.playbackState.collectAsStateWithLifecycle()
  val isTtsPlaying = ttsPlaybackState == TtsPlaybackState.PLAYING

  AnimatedVisibility(
    visible =
      ttsController.isAvailable &&
        (isTtsExpanded || isTtsPlaying || ttsPlaybackState == TtsPlaybackState.PAUSED),
    enter = expandVertically() + fadeIn(),
    exit = shrinkVertically() + fadeOut(),
  ) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
      TtsControlBar(
        controller = ttsController,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
      )
    }
  }
}
