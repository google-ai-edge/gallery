/*
 * Copyright 2026 Pascal Fritzsche
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

package com.google.ai.edge.gallery.mediagallery

import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.runtime.mutableStateMapOf
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.request.ImageRequest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val dateFormat = SimpleDateFormat("d. MMMM yyyy, HH:mm", Locale.GERMANY)

/**
 * Full screen pager over [items]. [actions] adds per-item buttons (e.g. similar, transcript) to
 * the overlay bar.
 */
@Composable
fun MediaViewerScreen(
  items: List<MediaItem>,
  startId: Long,
  onBack: () -> Unit,
  actions: @Composable (MediaItem) -> Unit = {},
  /** Video start positions in seconds, e.g. the scene a search found. */
  startTimes: Map<Long, Double> = emptyMap(),
  /** Targets for "move"; empty hides the action. */
  folders: List<MediaFolder> = emptyList(),
  /** After delete, move or favourite: reload the library. */
  onChanged: () -> Unit = {},
) {
  if (items.isEmpty()) {
    // e.g. the search behind this viewer was cleared
    LaunchedEffect(Unit) { onBack() }
    return
  }
  val start = items.indexOfFirst { it.id == startId }.coerceAtLeast(0)
  val pager = rememberPagerState(initialPage = start) { items.size }
  var chrome by remember { mutableStateOf(true) }
  val context = LocalContext.current
  val current = items[pager.currentPage.coerceIn(0, items.lastIndex)]
  val players = remember { mutableStateMapOf<Long, ExoPlayer>() }
  val mediaActions = rememberMediaActions(onChanged)
  var menu by remember { mutableStateOf(false) }
  var showMove by remember { mutableStateOf(false) }
  var showInfo by remember { mutableStateOf(false) }
  if (showMove) {
    MoveDialog(folders, onDismiss = { showMove = false }) { path ->
      showMove = false
      mediaActions.move(listOf(current), path)
    }
  }
  if (showInfo) InfoDialog(current) { showInfo = false }
  val transcript: TranscriptViewModel = hiltViewModel()
  var showTranscript by remember { mutableStateOf(false) }
  LaunchedEffect(current.id) {
    transcript.show(current)
    if (!current.isVideo) showTranscript = false
  }

  Box(Modifier.fillMaxSize().background(Color.Black)) {
    HorizontalPager(state = pager, beyondViewportPageCount = 1, key = { items[it].id }) { page ->
      val item = items[page]
      if (item.isVideo) {
        VideoPage(item, active = page == pager.currentPage, players, startSec = startTimes[item.id])
      } else {
        ZoomableImage(item, onTap = { chrome = !chrome })
      }
    }
    AnimatedVisibility(chrome, enter = fadeIn(), exit = fadeOut()) {
      Column(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.45f)).statusBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück", tint = Color.White)
          }
          Column(Modifier.weight(1f)) {
            Text(current.name, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
            Text(dateFormat.format(Date(current.takenAt)), color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.bodySmall)
          }
          actions(current)
          if (current.isVideo && transcript.available) {
            IconButton(onClick = { showTranscript = !showTranscript }) {
              Icon(Icons.Filled.Subtitles, "Transkript", tint = Color.White)
            }
          }
          IconButton(onClick = { mediaActions.favorite(listOf(current), !current.favorite) }) {
            Icon(if (current.favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, "Favorit", tint = Color.White)
          }
          IconButton(onClick = { mediaActions.share(listOf(current)) }) {
            Icon(Icons.Filled.Share, "Teilen", tint = Color.White)
          }
          IconButton(onClick = { mediaActions.trash(listOf(current)) }) {
            Icon(Icons.Filled.Delete, "Löschen", tint = Color.White)
          }
          Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Mehr", tint = Color.White) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
              if (folders.isNotEmpty()) {
                DropdownMenuItem(text = { Text("Verschieben") }, onClick = { menu = false; showMove = true })
              }
              DropdownMenuItem(text = { Text("Bearbeiten mit …") }, onClick = { menu = false; mediaActions.edit(current) })
              DropdownMenuItem(text = { Text("Details") }, onClick = { menu = false; showInfo = true })
            }
          }
        }
      }
    }
    if (showTranscript && current.isVideo) {
      TranscriptPanel(
        viewModel = transcript,
        item = current,
        onSeek = { sec -> players[current.id]?.let { it.seekTo((sec * 1000).toLong()); it.play() } },
        onClose = { showTranscript = false },
        modifier = Modifier.align(Alignment.BottomCenter),
      )
    }
  }
}

@Composable
private fun ZoomableImage(item: MediaItem, onTap: () -> Unit) {
  var scale by remember { mutableFloatStateOf(1f) }
  var offset by remember { mutableStateOf(Offset.Zero) }
  val state = rememberTransformableState { zoom, pan, _ ->
    scale = (scale * zoom).coerceIn(1f, 6f)
    offset = if (scale == 1f) Offset.Zero else offset + pan
  }
  val context = LocalContext.current
  AsyncImage(
    model = ImageRequest.Builder(context).data(item.uri).size(2560).build(),
    imageLoader = rememberGalleryImageLoader(),
    contentDescription = item.name,
    contentScale = ContentScale.Fit,
    modifier =
      Modifier.fillMaxSize()
        .pointerInput(Unit) {
          detectTapGestures(
            onTap = { onTap() },
            onDoubleTap = {
              scale = if (scale > 1f) 1f else 2.5f
              offset = Offset.Zero
            },
          )
        }
        // while zoomed the image takes the drags, otherwise the pager swipes
        .transformable(state, canPan = { scale > 1f })
        .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
  )
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoPage(item: MediaItem, active: Boolean, players: MutableMap<Long, ExoPlayer>, startSec: Double? = null) {
  val context = LocalContext.current
  val player = remember(item.id) {
    ExoPlayer.Builder(context).build().apply {
      setMediaItem(ExoMediaItem.fromUri(item.uri), ((startSec ?: 0.0) * 1000).toLong())
      prepare()
    }
  }
  DisposableEffect(player) {
    players[item.id] = player
    onDispose {
      players.remove(item.id)
      player.release()
    }
  }
  LaunchedEffect(active) { if (active) player.play() else player.pause() }
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  DisposableEffect(lifecycle, player) {
    // no sound from a video the user cannot see any more
    val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) player.pause() }
    lifecycle.addObserver(observer)
    onDispose { lifecycle.removeObserver(observer) }
  }
  AndroidView(
    factory = { PlayerView(it).apply { this.player = player } },
    modifier = Modifier.fillMaxSize().navigationBarsPadding().padding(top = 56.dp),
  )
}

@Composable
private fun InfoDialog(item: MediaItem, onDismiss: () -> Unit) {
  val rows =
    listOfNotNull(
      "Name" to item.name,
      "Aufgenommen" to dateFormat.format(Date(item.takenAt)),
      "Größe" to "%.1f MB".format(item.size / 1_000_000.0),
      if (item.width > 0) "Auflösung" to "${item.width} × ${item.height}" else null,
      if (item.isVideo) "Dauer" to formatDuration(item.durationMs) else null,
      "Format" to item.mime,
      "Ordner" to item.relativePath.ifBlank { item.bucketName },
    )
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Details") },
    text = {
      Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
        rows.forEach { (k, v) ->
          Column {
            Text(k, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(v, style = MaterialTheme.typography.bodyMedium)
          }
        }
      }
    },
    confirmButton = { TextButton(onClick = onDismiss) { Text("Schließen") } },
  )
}
