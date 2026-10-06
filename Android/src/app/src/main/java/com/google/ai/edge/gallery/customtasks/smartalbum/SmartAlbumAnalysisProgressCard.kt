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

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.common.logButtonClick
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * Shared "Analysis in progress" card showing current or aggregated photo indexing status, progress
 * percentage, pause/resume button, and notification request action.
 */
@Composable
fun SmartAlbumAnalysisProgressCard(
  progressUpdate: IndexingProgressUpdate,
  isIndexingPaused: Boolean,
  onPauseResumeClick: () -> Unit,
  isNotificationRequested: Boolean,
  onNotificationRequested: () -> Unit,
  onNotificationCancelled: () -> Unit,
  snackbarHostState: SnackbarHostState,
  modifier: Modifier = Modifier,
  onDismiss: (() -> Unit)? = null,
) {
  val coroutineScope = rememberCoroutineScope()
  val context = LocalContext.current
  val density = LocalDensity.current
  val offsetX = remember { Animatable(0f) }

  LaunchedEffect(Unit) { offsetX.snapTo(0f) }

  val analyzedCount = progressUpdate.processedCount
  val totalCount = progressUpdate.totalCount.coerceAtLeast(1)
  val percent = (analyzedCount * 100 / totalCount).coerceIn(0, 100)

  val toastMessage = stringResource(R.string.smartalbum_notify_me_toast)
  val undoLabel = stringResource(R.string.undo)

  val showToastWithUndo: () -> Unit = {
    onNotificationRequested()
    coroutineScope.launch {
      snackbarHostState.currentSnackbarData?.dismiss()
      val result =
        snackbarHostState.showSnackbar(
          message = toastMessage,
          actionLabel = undoLabel,
          duration = SnackbarDuration.Short,
        )
      if (result == SnackbarResult.ActionPerformed) {
        logButtonClick("smartalbum_notify_me_undo")
        onNotificationCancelled()
      }
    }
  }

  val notificationPermissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
      if (isGranted) {
        showToastWithUndo()
      }
    }

  val dismissModifier =
    if (onDismiss != null) {
      Modifier.offset { IntOffset(offsetX.value.roundToInt(), 0) }
        .graphicsLayer {
          val progress = (abs(offsetX.value) / with(density) { 250.dp.toPx() }).coerceIn(0f, 1f)
          alpha = 1f - progress * 0.5f
        }
        .pointerInput(Unit) {
          detectHorizontalDragGestures(
            onHorizontalDrag = { _, dragAmount ->
              coroutineScope.launch { offsetX.snapTo(offsetX.value + dragAmount) }
            },
            onDragEnd = {
              val dismissThresholdPx = with(density) { 80.dp.toPx() }
              coroutineScope.launch {
                if (abs(offsetX.value) > dismissThresholdPx) {
                  val targetX =
                    if (offsetX.value > 0) {
                      with(density) { 600.dp.toPx() }
                    } else {
                      with(density) { -600.dp.toPx() }
                    }
                  offsetX.animateTo(
                    targetValue = targetX,
                    animationSpec = tween(durationMillis = 180),
                  )
                  onDismiss()
                } else {
                  offsetX.animateTo(
                    targetValue = 0f,
                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                  )
                }
              }
            },
            onDragCancel = {
              coroutineScope.launch {
                offsetX.animateTo(
                  targetValue = 0f,
                  animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                )
              }
            },
          )
        }
    } else {
      Modifier
    }

  Surface(
    shape = RoundedCornerShape(16.dp),
    color = MaterialTheme.colorScheme.surfaceContainer,
    shadowElevation = 6.dp,
    modifier =
      modifier.fillMaxWidth().then(dismissModifier).padding(horizontal = 16.dp, vertical = 6.dp),
  ) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
      // Status Icon + Title
      Row(verticalAlignment = Alignment.CenterVertically) {
        if (isIndexingPaused) {
          Icon(
            imageVector = Icons.Default.Pause,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(20.dp),
          )
        } else {
          CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            strokeWidth = 2.5.dp,
            color = MaterialTheme.colorScheme.primary,
          )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text =
            if (isIndexingPaused) stringResource(R.string.smartalbum_analysis_stopped)
            else if (progressUpdate.isInitializing)
              stringResource(R.string.smartalbum_initializing_model)
            else stringResource(R.string.smartalbum_analyzing_in_progress),
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.onSurface,
        )
      }

      // Progress text
      Text(
        text =
          if (progressUpdate.isInitializing) {
            stringResource(R.string.smartalbum_initializing_model_description)
          } else {
            stringResource(
              R.string.smartalbum_analyzing_progress_format,
              "$analyzedCount",
              "$totalCount",
              percent,
            )
          },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
      )

      // Actions Row (Pause/Resume + Notify me)
      Row(verticalAlignment = Alignment.CenterVertically) {
        // Pause / Resume button
        Surface(
          shape = RoundedCornerShape(20.dp),
          color = MaterialTheme.colorScheme.primaryContainer,
          modifier =
            Modifier.clickable {
              logButtonClick(
                if (isIndexingPaused) "smartalbum_resume_analyzing"
                else "smartalbum_pause_analyzing"
              )
              onPauseResumeClick()
            },
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            if (isIndexingPaused) {
              Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(16.dp),
              )
              Spacer(modifier = Modifier.width(6.dp))
              Text(
                text = stringResource(R.string.smartalbum_resume_analyzing),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
              )
            } else {
              Icon(
                imageVector = Icons.Default.Pause,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(16.dp),
              )
              Spacer(modifier = Modifier.width(6.dp))
              Text(
                text = stringResource(R.string.smartalbum_stop_analyzing),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
              )
            }
          }
        }

        Spacer(modifier = Modifier.width(8.dp))

        if (!isNotificationRequested) {
          Row(
            modifier =
              Modifier.clickable {
                  logButtonClick("smartalbum_notify_me")
                  if (
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                      ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS,
                      ) != PackageManager.PERMISSION_GRANTED
                  ) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                  } else {
                    showToastWithUndo()
                  }
                }
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Icon(
              imageVector = Icons.Default.Notifications,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.primary,
              modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
              text = stringResource(R.string.smartalbum_notify_me),
              style = MaterialTheme.typography.labelMedium,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.primary,
            )
          }
        }
      }
    }
  }
}

/** Item status model for each collection in [SmartAlbumExpandableAnalysisProgressCard]. */
data class CollectionIndexingStatus(
  val source: SmartAlbumSource,
  val label: String,
  val percent: Int,
  val isRunning: Boolean,
  val buttonText: String?,
  val buttonIcon: ImageVector?,
  val onButtonClick: (() -> Unit)?,
)

/**
 * Expandable "Analysis in progress" toast used in the collection selection screen.
 *
 * In default (collapsed) form, displays an aggregated / active collection summary with a single
 * pause/resume button that affects all collections (matching the right side of the spec).
 *
 * In expanded form, displays each collection on its own status line with its own individual
 * pause/resume/start button and progress bar (matching the left side of the spec).
 */
@Composable
fun SmartAlbumExpandableAnalysisProgressCard(
  sampleProgress: IndexingProgressUpdate,
  userProgress: IndexingProgressUpdate,
  isSamplePaused: Boolean,
  isUserPaused: Boolean,
  isAllPaused: Boolean,
  isSampleAlbumAvailable: Boolean,
  hasUserPermission: Boolean,
  activeSource: SmartAlbumSource,
  onPauseResumeAllClick: () -> Unit,
  onPauseCollectionClick: (SmartAlbumSource) -> Unit,
  onResumeCollectionClick: (SmartAlbumSource) -> Unit,
  onStartNowCollectionClick: (SmartAlbumSource) -> Unit,
  modifier: Modifier = Modifier,
  isExpanded: Boolean = false,
  onToggleExpand: () -> Unit = {},
  onDismiss: (() -> Unit)? = null,
) {
  val coroutineScope = rememberCoroutineScope()
  val density = LocalDensity.current
  val offsetX = remember { Animatable(0f) }

  LaunchedEffect(Unit) { offsetX.snapTo(0f) }

  val dismissModifier =
    if (onDismiss != null) {
      Modifier.offset { IntOffset(offsetX.value.roundToInt(), 0) }
        .graphicsLayer {
          val progress = (abs(offsetX.value) / with(density) { 250.dp.toPx() }).coerceIn(0f, 1f)
          alpha = 1f - progress * 0.5f
        }
        .pointerInput(Unit) {
          detectHorizontalDragGestures(
            onHorizontalDrag = { _, dragAmount ->
              coroutineScope.launch { offsetX.snapTo(offsetX.value + dragAmount) }
            },
            onDragEnd = {
              val dismissThresholdPx = with(density) { 80.dp.toPx() }
              coroutineScope.launch {
                if (abs(offsetX.value) > dismissThresholdPx) {
                  val targetX =
                    if (offsetX.value > 0) {
                      with(density) { 600.dp.toPx() }
                    } else {
                      with(density) { -600.dp.toPx() }
                    }
                  offsetX.animateTo(
                    targetValue = targetX,
                    animationSpec = tween(durationMillis = 180),
                  )
                  onDismiss()
                } else {
                  offsetX.animateTo(
                    targetValue = 0f,
                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                  )
                }
              }
            },
            onDragCancel = {
              coroutineScope.launch {
                offsetX.animateTo(
                  targetValue = 0f,
                  animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                )
              }
            },
          )
        }
    } else {
      Modifier
    }

  // Compute status for Sample photos
  val sampleTotal = sampleProgress.totalCount
  val sampleProcessed = sampleProgress.processedCount
  val samplePercent =
    if (sampleTotal > 0) (sampleProcessed * 100 / sampleTotal).coerceIn(0, 100) else 0
  val isSampleComplete = sampleTotal > 0 && sampleProcessed >= sampleTotal

  // Compute status for My photos
  val userTotal = userProgress.totalCount
  val userProcessed = userProgress.processedCount
  val userPercent = if (userTotal > 0) (userProcessed * 100 / userTotal).coerceIn(0, 100) else 0
  val isUserComplete = userTotal > 0 && userProcessed >= userTotal

  val sampleIncomplete = !isSampleComplete && sampleTotal > 0
  val userIncomplete = !isUserComplete && (userTotal > 0 || hasUserPermission)

  val isSampleRunning =
    !isSamplePaused &&
      sampleIncomplete &&
      (activeSource == SmartAlbumSource.SAMPLE_ALBUM || isUserPaused || !userIncomplete)
  val isUserRunning =
    !isUserPaused &&
      userIncomplete &&
      (activeSource == SmartAlbumSource.USER_PHOTOS || isSamplePaused || !sampleIncomplete)

  val sampleName = stringResource(R.string.smartalbum_sample_photos)
  val userName = stringResource(R.string.smartalbum_my_photos)

  val sampleStatus =
    when {
      isSampleComplete ->
        CollectionIndexingStatus(
          source = SmartAlbumSource.SAMPLE_ALBUM,
          label =
            stringResource(R.string.smartalbum_collection_progress_active_format, sampleName, 100),
          percent = 100,
          isRunning = false,
          buttonText = null,
          buttonIcon = null,
          onButtonClick = null,
        )
      isSampleRunning ->
        CollectionIndexingStatus(
          source = SmartAlbumSource.SAMPLE_ALBUM,
          label =
            stringResource(
              R.string.smartalbum_collection_progress_active_format,
              sampleName,
              samplePercent,
            ),
          percent = samplePercent,
          isRunning = true,
          buttonText = stringResource(R.string.smartalbum_pause),
          buttonIcon = Icons.Default.Pause,
          onButtonClick = {
            logButtonClick(
              "smartalbum_pause_collection",
              buttonId = SmartAlbumSource.SAMPLE_ALBUM.name.lowercase(),
            )
            onPauseCollectionClick(SmartAlbumSource.SAMPLE_ALBUM)
          },
        )
      isSamplePaused ->
        CollectionIndexingStatus(
          source = SmartAlbumSource.SAMPLE_ALBUM,
          label =
            stringResource(
              R.string.smartalbum_collection_progress_paused_format,
              sampleName,
              samplePercent,
            ),
          percent = samplePercent,
          isRunning = false,
          buttonText =
            if (samplePercent == 0) stringResource(R.string.smartalbum_start_now)
            else stringResource(R.string.smartalbum_resume),
          buttonIcon = Icons.Default.PlayArrow,
          onButtonClick = {
            logButtonClick(
              "smartalbum_resume_collection",
              buttonId = SmartAlbumSource.SAMPLE_ALBUM.name.lowercase(),
            )
            onResumeCollectionClick(SmartAlbumSource.SAMPLE_ALBUM)
          },
        )
      else -> // Queued
      CollectionIndexingStatus(
          source = SmartAlbumSource.SAMPLE_ALBUM,
          label =
            stringResource(
              R.string.smartalbum_collection_progress_queued_format,
              sampleName,
              samplePercent,
            ),
          percent = samplePercent,
          isRunning = false,
          buttonText =
            if (samplePercent == 0) stringResource(R.string.smartalbum_start_now)
            else stringResource(R.string.smartalbum_resume),
          buttonIcon = Icons.Default.PlayArrow,
          onButtonClick = {
            logButtonClick(
              "smartalbum_start_collection",
              buttonId = SmartAlbumSource.SAMPLE_ALBUM.name.lowercase(),
            )
            onStartNowCollectionClick(SmartAlbumSource.SAMPLE_ALBUM)
          },
        )
    }

  val userStatus =
    when {
      isUserComplete ->
        CollectionIndexingStatus(
          source = SmartAlbumSource.USER_PHOTOS,
          label =
            stringResource(R.string.smartalbum_collection_progress_active_format, userName, 100),
          percent = 100,
          isRunning = false,
          buttonText = null,
          buttonIcon = null,
          onButtonClick = null,
        )
      isUserRunning ->
        CollectionIndexingStatus(
          source = SmartAlbumSource.USER_PHOTOS,
          label =
            stringResource(
              R.string.smartalbum_collection_progress_active_format,
              userName,
              userPercent,
            ),
          percent = userPercent,
          isRunning = true,
          buttonText = stringResource(R.string.smartalbum_pause),
          buttonIcon = Icons.Default.Pause,
          onButtonClick = {
            logButtonClick(
              "smartalbum_pause_collection",
              buttonId = SmartAlbumSource.USER_PHOTOS.name.lowercase(),
            )
            onPauseCollectionClick(SmartAlbumSource.USER_PHOTOS)
          },
        )
      isUserPaused ->
        CollectionIndexingStatus(
          source = SmartAlbumSource.USER_PHOTOS,
          label =
            stringResource(
              R.string.smartalbum_collection_progress_paused_format,
              userName,
              userPercent,
            ),
          percent = userPercent,
          isRunning = false,
          buttonText =
            if (userPercent == 0) stringResource(R.string.smartalbum_start_now)
            else stringResource(R.string.smartalbum_resume),
          buttonIcon = Icons.Default.PlayArrow,
          onButtonClick = {
            logButtonClick(
              "smartalbum_resume_collection",
              buttonId = SmartAlbumSource.USER_PHOTOS.name.lowercase(),
            )
            onResumeCollectionClick(SmartAlbumSource.USER_PHOTOS)
          },
        )
      else -> // Queued
      CollectionIndexingStatus(
          source = SmartAlbumSource.USER_PHOTOS,
          label =
            stringResource(
              R.string.smartalbum_collection_progress_queued_format,
              userName,
              userPercent,
            ),
          percent = userPercent,
          isRunning = false,
          buttonText =
            if (userPercent == 0) stringResource(R.string.smartalbum_start_now)
            else stringResource(R.string.smartalbum_resume),
          buttonIcon = Icons.Default.PlayArrow,
          onButtonClick = {
            logButtonClick(
              "smartalbum_start_collection",
              buttonId = SmartAlbumSource.USER_PHOTOS.name.lowercase(),
            )
            onStartNowCollectionClick(SmartAlbumSource.USER_PHOTOS)
          },
        )
    }

  val collectionItems = buildList {
    if (isSampleAlbumAvailable || sampleTotal > 0) {
      add(sampleStatus)
    }
    if (hasUserPermission || userTotal > 0) {
      add(userStatus)
    }
  }

  // Collapsed subtitle: active/running collection or first incomplete collection
  val collapsedSubtitle =
    when {
      isSampleRunning ->
        stringResource(
          R.string.smartalbum_collection_progress_done_format,
          sampleName,
          samplePercent,
        )
      isUserRunning ->
        stringResource(R.string.smartalbum_collection_progress_done_format, userName, userPercent)
      activeSource == SmartAlbumSource.SAMPLE_ALBUM && sampleIncomplete ->
        stringResource(
          R.string.smartalbum_collection_progress_done_format,
          sampleName,
          samplePercent,
        )
      userIncomplete ->
        stringResource(R.string.smartalbum_collection_progress_done_format, userName, userPercent)
      sampleIncomplete ->
        stringResource(
          R.string.smartalbum_collection_progress_done_format,
          sampleName,
          samplePercent,
        )
      else ->
        stringResource(
          R.string.smartalbum_collection_progress_done_format,
          sampleName,
          samplePercent,
        )
    }

  val arrowRotation by
    animateFloatAsState(
      targetValue = if (isExpanded) 0f else -90f,
      animationSpec = tween(durationMillis = 200),
      label = "arrowRotation",
    )

  val headerTitle =
    if (isAllPaused) stringResource(R.string.smartalbum_analysis_stopped)
    else stringResource(R.string.smartalbum_analyzing_photos)

  Surface(
    shape = RoundedCornerShape(16.dp),
    color = MaterialTheme.colorScheme.surfaceContainer,
    shadowElevation = 6.dp,
    modifier =
      modifier
        .fillMaxWidth()
        .then(dismissModifier)
        .padding(horizontal = 16.dp, vertical = 6.dp)
        .animateContentSize(),
  ) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
      // Header Row
      Row(
        modifier =
          Modifier.fillMaxWidth().clickable {
            logButtonClick(
              if (isExpanded) "smartalbum_collapse_progress_card"
              else "smartalbum_expand_progress_card"
            )
            onToggleExpand()
          },
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Icon(
          imageVector = Icons.Default.ArrowDropDown,
          contentDescription = "Expand or collapse",
          tint = MaterialTheme.colorScheme.onSurface,
          modifier = Modifier.size(22.dp).rotate(arrowRotation),
        )
        Spacer(modifier = Modifier.width(6.dp))

        if (isExpanded) {
          Text(
            text = headerTitle,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
          )
        } else {
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = headerTitle,
              style = MaterialTheme.typography.titleSmall,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
              text = collapsedSubtitle,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }

          Spacer(modifier = Modifier.width(8.dp))

          // Pause / Resume All Button
          Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier =
              Modifier.clickable {
                logButtonClick(
                  if (isAllPaused) "smartalbum_resume_all_collections"
                  else "smartalbum_pause_all_collections"
                )
                onPauseResumeAllClick()
              },
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Icon(
                imageVector = if (isAllPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(16.dp),
              )
              Spacer(modifier = Modifier.width(6.dp))
              Text(
                text =
                  if (isAllPaused) stringResource(R.string.smartalbum_resume)
                  else stringResource(R.string.smartalbum_pause),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
              )
            }
          }
        }
      }

      if (isExpanded) {
        Spacer(modifier = Modifier.height(10.dp))
        for (item in collectionItems) {
          CollectionStatusCard(item = item)
          Spacer(modifier = Modifier.height(8.dp))
        }
      }
    }
  }
}

@Composable
private fun CollectionStatusCard(item: CollectionIndexingStatus, modifier: Modifier = Modifier) {
  Surface(
    shape = RoundedCornerShape(12.dp),
    color = MaterialTheme.colorScheme.surfaceContainerHigh,
    modifier = modifier.fillMaxWidth(),
  ) {
    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
      Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (item.isRunning) {
          CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary,
          )
        } else {
          Icon(
            imageVector = Icons.Default.Pause,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(16.dp),
          )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text = item.label,
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.onSurface,
          modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(8.dp))
        if (item.buttonText != null) {
          Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.clickable { item.onButtonClick?.invoke() },
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              if (item.buttonIcon != null) {
                Icon(
                  imageVector = item.buttonIcon,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.onPrimaryContainer,
                  modifier = Modifier.size(14.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
              }
              Text(
                text = item.buttonText,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
              )
            }
          }
        }
      }
      Spacer(modifier = Modifier.height(10.dp))
      LinearProgressIndicator(
        progress = { (item.percent / 100f).coerceIn(0f, 1f) },
        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
        color = MaterialTheme.colorScheme.primary,
        trackColor = MaterialTheme.colorScheme.surfaceVariant,
      )
    }
  }
}
