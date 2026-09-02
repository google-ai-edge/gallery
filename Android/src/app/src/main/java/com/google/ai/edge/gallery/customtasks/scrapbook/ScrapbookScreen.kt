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
package com.google.ai.edge.gallery.customtasks.scrapbook

import android.graphics.Bitmap
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import kotlinx.coroutines.launch

private const val TAG = "AGSS"

private data class Tab(
  @StringRes val labelStringResId: Int,
  val icon: ImageVector? = null,
  @DrawableRes val vectorResourceId: Int? = null,
)

private val TABS =
  listOf(
    Tab(labelStringResId = R.string.tab_create_cutout, vectorResourceId = R.drawable.add_cutout),
    Tab(labelStringResId = R.string.tab_collage, vectorResourceId = R.drawable.collage),
    Tab(labelStringResId = R.string.tab_my_cutouts, vectorResourceId = R.drawable.my_cutouts),
  )

val TAB_BAR_HEIGHT = 78.dp

@Composable
fun ScrapbookScreen(
  task: Task,
  modelManagerViewModel: ModelManagerViewModel,
  bottomPadding: Dp,
  setAppBarControlsDisabled: (Boolean) -> Unit,
  setTopBarVisible: (Boolean) -> Unit,
  setCustomNavigateUpCallback: ((() -> Unit)?) -> Unit,
  viewModel: ScrapbookViewModel = hiltViewModel(),
) {
  val uiState by viewModel.uiState.collectAsState()
  val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
  val model = modelManagerUiState.selectedModel
  var selectedTabIndex by remember { mutableIntStateOf(0) }
  val snackbarHostState = remember { SnackbarHostState() }
  val scope = rememberCoroutineScope()

  Box(contentAlignment = Alignment.Center) {
    Box(modifier = Modifier.fillMaxSize()) {
      // Tab content.
      Box(modifier = Modifier.fillMaxSize().align(Alignment.TopCenter)) {
        AnimatedContent(
          targetState = selectedTabIndex,
          transitionSpec = {
            slideInVertically(animationSpec = tween(delayMillis = 50)) { 20 } +
              fadeIn(animationSpec = tween(delayMillis = 50)) togetherWith
              fadeOut(animationSpec = tween(100))
          },
        ) { tabIndex ->
          when (tabIndex) {
            0 -> {
              Segmenter(
                model = model,
                viewModel = viewModel,
                bottomPadding = bottomPadding,
                setCustomNavigateUpCallback = setCustomNavigateUpCallback,
              )
            }
            1 -> {
              CollageEditor(
                viewModel = viewModel,
                bottomPadding = bottomPadding,
                setCustomNavigateUpCallback = setCustomNavigateUpCallback,
                onCutoutsDeleted = {
                  // Switch to cutout creation tab when there is no cutouts left.
                  if (uiState.cutoutInfos.isEmpty()) {
                    selectedTabIndex = 0
                  }
                },
              )
            }
            2 -> {
              CutoutManager(
                viewModel = viewModel,
                bottomPadding = bottomPadding,
                setCustomNavigateUpCallback = setCustomNavigateUpCallback,
                onCutoutsDeleted = {
                  // Switch to cutout creation tab when there is no cutouts left.
                  if (uiState.cutoutInfos.isEmpty()) {
                    selectedTabIndex = 0
                  }
                },
              )
            }
            else -> {}
          }
        }
      }

      // Bottom tab.
      //
      // Hide when cutout editor is shown.
      AnimatedVisibility(
        !uiState.showCandidateCutoutEditor,
        modifier = Modifier.align(Alignment.BottomCenter),
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
      ) {
        Box(
          modifier =
            Modifier.fillMaxWidth()
              .background(MaterialTheme.colorScheme.surfaceContainerHigh)
              .padding(horizontal = 12.dp)
              .padding(bottom = bottomPadding)
        ) {
          Row(
            modifier = Modifier.fillMaxWidth().height(TAB_BAR_HEIGHT),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            for ((tabIndex, tab) in TABS.withIndex()) {
              val selected = tabIndex == selectedTabIndex
              Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                val disabled = uiState.cutoutInfos.isEmpty() && (tabIndex == 1 || tabIndex == 2)
                val collageWarning = stringResource(R.string.tab_collage_empty_warning)
                val cutoutManagerWarning = stringResource(R.string.tab_my_cutouts_empty_warning)
                Column(
                  horizontalAlignment = Alignment.CenterHorizontally,
                  verticalArrangement = Arrangement.spacedBy(2.dp),
                  modifier =
                    Modifier.graphicsLayer { alpha = if (disabled) 0.3f else 1f }
                      .clip(RoundedCornerShape(8.dp))
                      .clickable {
                        if (!disabled) {
                          selectedTabIndex = tabIndex
                        } else {
                          if (tabIndex == 1) {
                            scope.launch {
                              snackbarHostState.currentSnackbarData?.dismiss()
                              snackbarHostState.showSnackbar(
                                collageWarning,
                                withDismissAction = true,
                              )
                            }
                          } else {
                            scope.launch {
                              snackbarHostState.currentSnackbarData?.dismiss()
                              snackbarHostState.showSnackbar(
                                cutoutManagerWarning,
                                withDismissAction = true,
                              )
                            }
                          }
                        }
                      }
                      .padding(horizontal = 16.dp, vertical = 6.dp),
                ) {
                  val tintColor =
                    if (selected) MaterialTheme.colorScheme.secondary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                  Box(
                    contentAlignment = Alignment.Center,
                    modifier =
                      Modifier.size(width = 56.dp, height = 32.dp)
                        .clip(CircleShape)
                        .background(
                          if (selectedTabIndex == tabIndex)
                            MaterialTheme.colorScheme.secondaryContainer
                          else Color.Transparent
                        ),
                  ) {
                    Icon(
                      if (tab.vectorResourceId != null)
                        ImageVector.vectorResource(tab.vectorResourceId)
                      else tab.icon!!,
                      contentDescription = null,
                      tint = tintColor,
                      modifier = Modifier.size(24.dp),
                    )
                  }
                  Text(
                    stringResource(tab.labelStringResId),
                    style = MaterialTheme.typography.labelMedium.copy(lineHeight = 14.sp),
                    color = tintColor,
                    textAlign = TextAlign.Center,
                  )
                }

                // Show collection size in a number badge.
                if (
                  tabIndex == 2 && (uiState.cutoutInfos.isNotEmpty() || uiState.collectingCutout)
                ) {
                  Box(Modifier.offset(x = (-12).dp, y = 2.dp).align(Alignment.TopCenter)) {
                    AnimatedContent(uiState.collectingCutout) { collecting ->
                      val color =
                        if (selected) MaterialTheme.colorScheme.secondary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                      if (collecting) {
                        CircularProgressIndicator(
                          modifier = Modifier.size(12.dp),
                          color = color,
                          trackColor = Color.Transparent,
                          strokeWidth = 2.dp,
                        )
                      } else {
                        Text(
                          "${uiState.cutoutInfos.size}",
                          style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                          color = color,
                          modifier =
                            Modifier.clip(CircleShape)
                              .background(MaterialTheme.colorScheme.surface)
                              .border(width = 2.dp, color = color, shape = CircleShape)
                              .padding(horizontal = 7.dp),
                        )
                      }
                    }
                  }
                }
              }
            }
          }
        }
      }
    }

    SnackbarHost(
      hostState = snackbarHostState,
      modifier =
        Modifier.padding(bottom = bottomPadding + TAB_BAR_HEIGHT + 4.dp)
          .align(Alignment.BottomCenter),
    )
  }
}

fun getFitRect(bitmap: Bitmap, canvasSize: Size, toTop: Boolean = false): Rect {
  val bitmapWidth = bitmap.width.toFloat()
  val bitmapHeight = bitmap.height.toFloat()
  val canvasWidth = canvasSize.width
  val canvasHeight = canvasSize.height

  // Calculate the scale to FIT the entire image inside the canvas.
  // The scale is the minimum of the two required scales (width-wise and height-wise).
  val scaleX = canvasWidth / bitmapWidth
  val scaleY = canvasHeight / bitmapHeight
  // Use the MIN scale to ensure the entire image fits.
  val scale = minOf(scaleX, scaleY)

  // Calculate the size of the scaled image on the canvas.
  val scaledImageWidth = bitmapWidth * scale
  val scaledImageHeight = bitmapHeight * scale

  // Calculate the offsets to center the scaled image on the canvas.
  val offsetX = (canvasWidth - scaledImageWidth) / 2
  val offsetY = if (toTop) 0f else (canvasHeight - scaledImageHeight) / 2

  // Destination parameters (This defines the centered, scaled area on the canvas)
  return Rect(offsetX, offsetY, offsetX + scaledImageWidth, offsetY + scaledImageHeight)
}

@Composable
fun pixelToDp(pixelValue: Int): Dp {
  val density = LocalDensity.current
  val dpValue = with(density) { pixelValue.toDp() }
  return dpValue
}

@Composable
fun dpToPixel(dpValue: Dp): Float {
  val density = LocalDensity.current
  val pixelValue = with(density) { dpValue.toPx() }
  return pixelValue
}
