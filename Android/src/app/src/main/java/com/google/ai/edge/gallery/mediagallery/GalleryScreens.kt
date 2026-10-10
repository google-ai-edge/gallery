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

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Permissions needed to list photos and videos on this Android version. */
fun mediaPermissions(): Array<String> =
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
    arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
  else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

/** Asks for media access once and starts the repository when it is there. */
@Composable
fun MediaPermissionGate(viewModel: GalleryViewModel, content: @Composable () -> Unit) {
  val context = LocalContext.current
  fun granted() =
    mediaPermissions().all {
      ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    } ||
      (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
        ContextCompat.checkSelfPermission(
          context,
          Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        ) == PackageManager.PERMISSION_GRANTED)
  var hasAccess by remember { mutableStateOf(granted()) }
  val launcher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
      hasAccess = granted()
    }
  LaunchedEffect(hasAccess) {
    if (hasAccess) viewModel.onPermissionGranted() else launcher.launch(mediaPermissions())
  }
  if (hasAccess) {
    content()
  } else {
    Column(
      modifier = Modifier.fillMaxSize().padding(32.dp),
      verticalArrangement = Arrangement.Center,
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Icon(Icons.Filled.PhotoLibrary, contentDescription = null, modifier = Modifier.size(48.dp))
      Spacer(Modifier.height(16.dp))
      Text(
        "Die Galerie braucht Zugriff auf deine Fotos und Videos.",
        style = MaterialTheme.typography.bodyLarge,
      )
      Spacer(Modifier.height(16.dp))
      Button(onClick = { launcher.launch(mediaPermissions()) }) { Text("Zugriff erlauben") }
    }
  }
}

/** Start screen: all media plus one tile per folder. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoldersScreen(
  viewModel: GalleryViewModel,
  onOpenFolder: (bucketId: Long?) -> Unit,
  actions: @Composable () -> Unit = {},
  /** Albums made from bubbles, with a local cover if one of their previews is on the phone. */
  albums: List<Pair<com.google.ai.edge.gallery.morgenschiss.Album, MediaItem?>> = emptyList(),
  onOpenAlbum: (String) -> Unit = {},
  /** Shown above everything, e.g. "new files for your folders". */
  banner: @Composable () -> Unit = {},
) {
  val library by viewModel.library.collectAsState()
  val loader = rememberGalleryImageLoader()
  Scaffold(topBar = { TopAppBar(title = { Text("Galerie") }, actions = { actions() }) }) { padding ->
    if (!library.loaded) {
      Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
      }
      return@Scaffold
    }
    if (library.items.isEmpty()) {
      Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
        Text("Keine Fotos oder Videos gefunden.", style = MaterialTheme.typography.bodyLarge)
      }
      return@Scaffold
    }
    LazyVerticalGrid(
      columns = GridCells.Adaptive(minSize = 150.dp),
      contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = padding.calculateTopPadding(), bottom = 24.dp + padding.calculateBottomPadding()),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      item(key = "banner", span = { GridItemSpan(maxLineSpan) }) { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { banner() } }
      if (albums.isNotEmpty()) {
        item(key = "albums-h", span = { GridItemSpan(maxLineSpan) }) { Text("Alben", style = MaterialTheme.typography.titleMedium) }
        items(albums, key = { "album-" + it.first.id }) { (album, cover) ->
          FolderTile(album.name, album.count, cover ?: library.items.first(), loader) { onOpenAlbum(album.id) }
        }
        item(key = "folders-h", span = { GridItemSpan(maxLineSpan) }) { Text("Ordner", style = MaterialTheme.typography.titleMedium) }
      }
      item(key = "all") {
        FolderTile("Alle", library.items.size, library.items.first(), loader) { onOpenFolder(null) }
      }
      library.items.firstOrNull { it.favorite }?.let { cover ->
        item(key = "favorites") {
          FolderTile("Favoriten", library.items.count { it.favorite }, cover, loader) { onOpenFolder(MediaLibrary.FAVORITES) }
        }
      }
      items(library.folders, key = { it.bucketId }) { folder ->
        FolderTile(folder.name, folder.count, folder.cover, loader) { onOpenFolder(folder.bucketId) }
      }
    }
  }
}

@Composable
private fun FolderTile(
  name: String,
  count: Int,
  cover: MediaItem,
  loader: coil.ImageLoader,
  onClick: () -> Unit,
) {
  Column(modifier = Modifier.clickable(onClick = onClick)) {
    AsyncImage(
      model = MediaThumb(cover.uri, 512),
      imageLoader = loader,
      contentDescription = null,
      contentScale = ContentScale.Crop,
      modifier =
        Modifier.fillMaxWidth()
          .aspectRatio(1f)
          .clip(RoundedCornerShape(16.dp))
          .background(MaterialTheme.colorScheme.surfaceVariant),
    )
    Text(
      name,
      style = MaterialTheme.typography.titleSmall,
      fontWeight = FontWeight.SemiBold,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.padding(top = 8.dp, start = 2.dp),
    )
    Text(
      "$count",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(start = 2.dp),
    )
  }
}

private val monthFormat = SimpleDateFormat("MMMM yyyy", Locale.GERMANY)

/** Grid entries: a month header followed by its items. */
private sealed interface GridEntry {
  data class Header(val label: String) : GridEntry

  data class Cell(val item: MediaItem) : GridEntry
}

private fun withMonthHeaders(items: List<MediaItem>): List<GridEntry> {
  val out = ArrayList<GridEntry>(items.size + 64)
  var last = ""
  for (item in items) {
    val label = monthFormat.format(Date(item.takenAt))
    if (label != last) {
      out += GridEntry.Header(label)
      last = label
    }
    out += GridEntry.Cell(item)
  }
  return out
}

/** Media of one folder (or all) as a dense grid grouped by month. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderGridScreen(
  viewModel: GalleryViewModel,
  bucketId: Long?,
  onOpenItem: (MediaItem) -> Unit,
  onBack: () -> Unit,
  header: @Composable () -> Unit = {},
  overrideItems: List<MediaItem>? = null,
  /** Scene times of video hits (seconds), shown on the cell. */
  times: Map<Long, Double> = emptyMap(),
  titleOverride: String? = null,
  barActions: @Composable () -> Unit = {},
) {
  val library by viewModel.library.collectAsState()
  val loader = rememberGalleryImageLoader()
  val title =
    titleOverride ?: when (bucketId) {
      null -> "Alle"
      MediaLibrary.FAVORITES -> "Favoriten"
      else -> library.folders.firstOrNull { it.bucketId == bucketId }?.name ?: ""
    }
  val items = remember(library, bucketId, overrideItems) { overrideItems ?: library.itemsIn(bucketId) }
  // long press starts selecting; ids survive a library reload, vanished ones drop out
  var selected by rememberSaveable { mutableStateOf(setOf<Long>()) }
  val selectedItems = remember(items, selected) { items.filter { it.id in selected } }
  LaunchedEffect(items) { selected = selected.intersect(items.map { it.id }.toSet()) }
  BackHandler(enabled = selected.isNotEmpty()) { selected = emptySet() }
  val actions = rememberMediaActions(onChanged = { selected = emptySet(); viewModel.reloadLibrary() })
  var showMove by remember { mutableStateOf(false) }
  if (showMove) {
    MoveDialog(library.folders, onDismiss = { showMove = false }) { path ->
      showMove = false
      actions.move(selectedItems, path)
    }
  }
  val entries = remember(items, overrideItems) {
    // search results keep their ranking instead of month groups
    if (overrideItems != null) items.map { GridEntry.Cell(it) } else withMonthHeaders(items)
  }
  Scaffold(
    topBar = {
      if (selected.isEmpty()) {
        TopAppBar(
          title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
          navigationIcon = {
            IconButton(onClick = onBack) {
              Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
            }
          },
          actions = { barActions() },
        )
      } else {
        TopAppBar(
          title = { Text("${selected.size}", maxLines = 1) },
          navigationIcon = {
            IconButton(onClick = { selected = emptySet() }) { Icon(Icons.Filled.Close, contentDescription = "Auswahl beenden") }
          },
          actions = {
            IconButton(onClick = { selected = items.map { it.id }.toSet() }) { Icon(Icons.Filled.SelectAll, "Alle auswählen") }
            IconButton(onClick = { actions.share(selectedItems) }) { Icon(Icons.Filled.Share, "Teilen") }
            IconButton(onClick = { actions.favorite(selectedItems, !selectedItems.all { it.favorite }) }) {
              Icon(if (selectedItems.all { it.favorite }) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, "Favorit")
            }
            IconButton(onClick = { showMove = true }) { Icon(Icons.Filled.DriveFileMove, "Verschieben") }
            IconButton(onClick = { actions.trash(selectedItems) }) { Icon(Icons.Filled.Delete, "Löschen") }
          },
        )
      }
    }
  ) { padding ->
    Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
      header()
      LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 96.dp),
        contentPadding = PaddingValues(bottom = 24.dp + padding.calculateBottomPadding()),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
      ) {
        items(
          entries,
          key = {
            when (it) {
              is GridEntry.Header -> "h-${it.label}"
              is GridEntry.Cell -> it.item.id
            }
          },
          span = { if (it is GridEntry.Header) GridItemSpan(maxLineSpan) else GridItemSpan(1) },
          contentType = { if (it is GridEntry.Header) 0 else 1 },
        ) { entry ->
          when (entry) {
            is GridEntry.Header ->
              Text(
                entry.label,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 12.dp, top = 16.dp, bottom = 6.dp),
              )
            is GridEntry.Cell -> {
              val id = entry.item.id
              MediaCell(
                entry.item,
                loader,
                sceneSec = times[id],
                selected = if (selected.isEmpty()) null else id in selected,
                onLongClick = { selected = selected + id },
              ) {
                if (selected.isEmpty()) onOpenItem(entry.item)
                else selected = if (id in selected) selected - id else selected + id
              }
            }
          }
        }
      }
    }
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaCell(
  item: MediaItem,
  loader: coil.ImageLoader,
  sceneSec: Double? = null,
  /** null = not in selection mode. */
  selected: Boolean? = null,
  onLongClick: (() -> Unit)? = null,
  onClick: () -> Unit,
) {
  Box(modifier = Modifier.aspectRatio(1f).combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
    AsyncImage(
      // a scene hit shows the matching moment, not the video's first frame
      model = MediaThumb(item.uri, timeMs = sceneSec?.let { (it * 1000).toLong() }),
      imageLoader = loader,
      contentDescription = item.name,
      contentScale = ContentScale.Crop,
      modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
    )
    if (item.favorite && selected == null) {
      Icon(Icons.Filled.Favorite, null, tint = Color.White, modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).size(14.dp))
    }
    if (selected != null) {
      Box(Modifier.fillMaxSize().background(if (selected) Color.Black.copy(alpha = 0.35f) else Color.Transparent))
      Icon(
        if (selected) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
        contentDescription = if (selected) "Ausgewählt" else null,
        tint = if (selected) MaterialTheme.colorScheme.primary else Color.White,
        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp),
      )
    }
    if (item.isVideo) {
      Row(
        modifier =
          Modifier.align(Alignment.BottomEnd)
            .padding(4.dp)
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(14.dp))
        Text(
          sceneSec?.let { "bei " + formatDuration((it * 1000).toLong()) } ?: formatDuration(item.durationMs),
          color = Color.White,
          style = MaterialTheme.typography.labelSmall,
        )
      }
    }
  }
}

fun formatDuration(ms: Long): String {
  val total = ms / 1000
  val h = total / 3600
  val m = (total % 3600) / 60
  val s = total % 60
  return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** Opens the system share sheet for one item. */
fun shareIntent(item: MediaItem): Intent =
  Intent.createChooser(
    Intent(Intent.ACTION_SEND).apply {
      type = item.mime
      putExtra(Intent.EXTRA_STREAM, item.uri)
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    },
    null,
  )
