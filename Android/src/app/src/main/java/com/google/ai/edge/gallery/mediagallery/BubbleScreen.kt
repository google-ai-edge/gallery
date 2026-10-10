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

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.google.ai.edge.gallery.morgenschiss.Bubble
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

private const val YAW = 0.55f
private const val PITCH = -0.35f
private const val CAMERA = 4f

/** A bubble projected onto the screen for the current camera. */
private data class Projected(val index: Int, val x: Float, val y: Float, val r: Float, val depth: Float)

/** Fixed view direction; only the centre and the zoom move. */
private class Projector(val cx: Float, val cy: Float, val cz: Float, val scale: Float, val ox: Float, val oy: Float) {
  private val cyaw = cos(YAW)
  private val syaw = sin(YAW)
  private val cp = cos(PITCH)
  private val sp = sin(PITCH)

  /** Returns screen x, screen y, depth (bigger = nearer) and the perspective factor. */
  fun project(x: Float, y: Float, z: Float): FloatArray {
    val px = x - cx
    val py = y - cy
    val pz = z - cz
    val x1 = px * cyaw + pz * syaw
    val z1 = -px * syaw + pz * cyaw
    val y2 = py * cp - z1 * sp
    val z2 = py * sp + z1 * cp
    val f = CAMERA / (CAMERA - z2).coerceAtLeast(0.2f)
    return floatArrayOf(ox + x1 * f * scale, oy - y2 * f * scale, z2, f)
  }
}

/** Next bubble on screen in a direction (dx, dy), within 60 degrees; nearest wins. */
private fun neighbour(from: Projected, all: List<Projected>, dx: Float, dy: Float): Int? {
  val want = atan2(dy, dx)
  return all
    .filter { it.index != from.index }
    .mapNotNull { p ->
      val vx = p.x - from.x
      val vy = p.y - from.y
      var diff = abs(atan2(vy, vx) - want)
      if (diff > Math.PI) diff = (2 * Math.PI - diff).toFloat()
      if (diff > Math.PI / 3) null else p.index to hypot(vx, vy) * (1 + diff)
    }
    .minByOrNull { it.second }
    ?.first
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BubbleScreen(viewModel: BubbleViewModel, onBack: () -> Unit, onOpen: (List<MediaItem>, Int) -> Unit) {
  LaunchedEffect(Unit) { if (viewModel.state !is Loadable.Done) viewModel.load() }
  val parent = viewModel.parent()
  BackHandler { if (!viewModel.up()) onBack() }
  var renaming by remember { mutableStateOf<Bubble?>(null) }
  renaming?.let { b -> RenameDialog(b, onDismiss = { renaming = null }) { name -> viewModel.rename(b, name); renaming = null } }
  var albumFor by remember { mutableStateOf<Bubble?>(null) }
  val scope = rememberCoroutineScope()
  val actions = rememberMediaActions(onChanged = {})
  val context = androidx.compose.ui.platform.LocalContext.current
  LaunchedEffect(viewModel.message) {
    viewModel.message?.let { android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show(); viewModel.message = null }
  }
  albumFor?.let { b ->
    AlbumDialog(b, onDismiss = { albumFor = null }) { name, folder ->
      albumFor = null
      scope.launch {
        if (viewModel.createAlbum(b, name, folder) && folder) {
          // a real folder: the bubble's files move there now, new ones later from the banner
          val path = MediaActions.newFolderPath(name)
          val files = b.allMembers().mapNotNull { viewModel.items[it.id] }.filter { it.relativePath != path }
          actions.moveEach(files.associateWith { path })
        }
      }
    }
  }
  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(parent?.title ?: "Bubbles", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = { IconButton(onClick = { if (!viewModel.up()) onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") } },
      )
    }
  ) { padding ->
    Box(Modifier.fillMaxSize().padding(padding)) {
      when (val s = viewModel.state) {
        is Loadable.Done -> {
          val level = viewModel.level()
          if (level.isEmpty()) {
            Text("Noch zu wenige Medien am Server für Bubbles.", modifier = Modifier.padding(24.dp))
          } else {
            BubbleSpace(viewModel, level, onOpen, onRename = { renaming = it }, onAlbum = { albumFor = it })
          }
        }
        is Loadable.Error -> Text(s.message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
        else ->
          Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Text("Bubbles werden berechnet …", modifier = Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall)
          }
      }
    }
  }
}

@Composable
private fun BubbleSpace(
  viewModel: BubbleViewModel,
  level: List<Bubble>,
  onOpen: (List<MediaItem>, Int) -> Unit,
  onRename: (Bubble) -> Unit,
  onAlbum: (Bubble) -> Unit,
) {
  val focus = viewModel.focus.coerceIn(0, level.lastIndex)
  val focused = level[focus]
  // the camera glides to the focused bubble and zooms so it fills about a fifth of the screen
  val anim = tween<Float>(durationMillis = 650)
  val cx by animateFloatAsState(focused.x, anim, label = "cx")
  val cy by animateFloatAsState(focused.y, anim, label = "cy")
  val cz by animateFloatAsState(focused.z, anim, label = "cz")
  val zoom by animateFloatAsState((0.22f / (0.36f * focused.r.coerceAtLeast(0.02f))).coerceIn(0.8f, 10f), anim, label = "zoom")
  val members = remember(focused, viewModel.items) { focused.allMembers().mapNotNull { m -> viewModel.items[m.id]?.let { m to it } } }
  val loader = rememberGalleryImageLoader()
  val density = LocalDensity.current
  // flattening and sorting thousands of members must not happen every animation frame
  val levelMembers = remember(level) { level.map { it.allMembers() } }

  BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF0E1216))) {
    val w = constraints.maxWidth.toFloat()
    val h = constraints.maxHeight.toFloat()
    // the space sits above the sheet
    val projector = Projector(cx, cy, cz, minOf(w, h) * 0.36f * zoom, w / 2, h * (if (viewModel.sheetOpen) 0.22f else 0.42f))
    val projected =
      level.mapIndexed { i, b ->
        val p = projector.project(b.x, b.y, b.z)
        Projected(i, p[0], p[1], b.r * p[3] * projector.scale, p[2])
      }
    val fp = projected[focus]
    // bubbles between the camera and the focused one fade out
    val inTheWay = projected.filter { p -> p.index != focus && p.depth > fp.depth && hypot(p.x - fp.x, p.y - fp.y) < p.r + fp.r }.map { it.index }.toSet()
    val highlight = members.getOrNull(viewModel.page)?.first

    Canvas(
      Modifier.fillMaxSize().pointerInput(level) {
        detectTapGestures(
          onTap = { t ->
            projected.filter { hypot(it.x - t.x, it.y - t.y) <= maxOf(it.r, 24.dp.toPx()) && it.index !in inTheWay }
              .maxByOrNull { it.depth }
              ?.let { viewModel.focusOn(it.index) }
          },
          onDoubleTap = { if (focused.children.isNotEmpty()) viewModel.enter(focused) },
        )
      }
    ) {
      for (p in projected.sortedBy { it.depth }) {
        val b = level[p.index]
        val base = pointColor(b.x, b.y, b.z, "JPEG")
        val alpha = when {
          p.index in inTheWay -> 0.12f
          p.index == focus -> 0.55f
          else -> 0.35f
        }
        drawCircle(
          Brush.radialGradient(listOf(base.copy(alpha = alpha), base.copy(alpha = alpha * 0.35f)), center = Offset(p.x - p.r * 0.3f, p.y - p.r * 0.3f), radius = p.r * 1.3f),
          p.r,
          Offset(p.x, p.y),
        )
        if (p.index == focus) drawCircle(Color.White.copy(alpha = 0.8f), p.r, Offset(p.x, p.y), style = Stroke(1.5.dp.toPx()))
        // members as dots; only the focused bubble shows them clearly
        if (p.index !in inTheWay) {
          val dotR = if (p.index == focus) 2.2.dp.toPx() else 1.2.dp.toPx()
          for (m in levelMembers[p.index]) {
            val q = projector.project(m.x, m.y, m.z)
            drawCircle(Color.White.copy(alpha = if (p.index == focus) 0.75f else 0.35f), dotR, Offset(q[0], q[1]))
          }
        }
      }
      highlight?.let { m ->
        val q = projector.project(m.x, m.y, m.z)
        drawCircle(Color(0xFFFFD54F), 4.dp.toPx(), Offset(q[0], q[1]))
        drawCircle(Color(0xFFFFD54F), 9.dp.toPx(), Offset(q[0], q[1]), style = Stroke(2.dp.toPx()))
      }
    }

    // callouts: a line up from the bubble to a balloon with its best pictures
    // a neighbour's balloon must not sit on top of the focused bubble
    val clearOfFocus = { p: Projected -> hypot(p.x - fp.x, p.y - p.r - fp.y) > fp.r + with(density) { 40.dp.toPx() } }
    val shown = (listOf(fp) + projected.filter { it.index != focus && it.index !in inTheWay && clearOfFocus(it) }.sortedBy { hypot(it.x - fp.x, it.y - fp.y) }.take(4))
      .filter { it.x in 0f..w && it.y in 0f..h }
    for (p in shown) {
      val b = level[p.index]
      val top = p.y - p.r
      val lineLen = with(density) { 28.dp.toPx() }
      Canvas(Modifier.fillMaxSize()) {
        drawLine(Color.White.copy(alpha = 0.6f), Offset(p.x, top), Offset(p.x, top - lineLen), strokeWidth = 1.5.dp.toPx())
      }
      Callout(
        bubble = b,
        items = viewModel.items,
        loader = loader,
        focused = p.index == focus,
        modifier =
          Modifier.offset { IntOffset((p.x - with(density) { 64.dp.toPx() }).roundToInt(), (top - lineLen - with(density) { 64.dp.toPx() }).roundToInt()) }
            .clickable { viewModel.focusOn(p.index) },
      )
    }

    // arrows jump to the next bubble in that direction
    val dirs = listOf(
      Triple(Icons.Filled.KeyboardArrowLeft, -1f to 0f, Alignment.CenterStart),
      Triple(Icons.Filled.KeyboardArrowRight, 1f to 0f, Alignment.CenterEnd),
      Triple(Icons.Filled.KeyboardArrowUp, 0f to -1f, Alignment.TopCenter),
      Triple(Icons.Filled.KeyboardArrowDown, 0f to 1f, Alignment.BottomCenter),
    )
    Box(Modifier.fillMaxWidth().fillMaxHeight(if (viewModel.sheetOpen) 0.45f else 0.78f).padding(8.dp)) {
      for ((icon, d, align) in dirs) {
        val target = neighbour(fp, projected, d.first, d.second)
        if (target != null) {
          FilledTonalIconButton(onClick = { viewModel.focusOn(target) }, modifier = Modifier.align(align)) { Icon(icon, "Nächste Bubble") }
        }
      }
    }

    BubbleSheet(
      bubble = focused,
      members = members.map { it.second },
      viewModel = viewModel,
      loader = loader,
      onOpen = onOpen,
      onRename = { onRename(focused) },
      onAlbum = { onAlbum(focused) },
      modifier = Modifier.align(Alignment.BottomCenter),
    )
  }
}

@Composable
private fun Callout(bubble: Bubble, items: Map<String, MediaItem>, loader: coil.ImageLoader, focused: Boolean, modifier: Modifier) {
  Column(
    modifier
      .width(128.dp)
      .background(Color.Black.copy(alpha = if (focused) 0.8f else 0.6f), RoundedCornerShape(12.dp))
      .padding(4.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
      bubble.previews.take(3).mapNotNull { items[it] }.forEach { item ->
        AsyncImage(
          model = MediaThumb(item.uri, 192),
          imageLoader = loader,
          contentDescription = null,
          contentScale = ContentScale.Crop,
          modifier = Modifier.size(38.dp).clip(RoundedCornerShape(6.dp)),
        )
      }
    }
    Text(bubble.title, color = Color.White, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BubbleSheet(
  bubble: Bubble,
  members: List<MediaItem>,
  viewModel: BubbleViewModel,
  loader: coil.ImageLoader,
  onOpen: (List<MediaItem>, Int) -> Unit,
  onRename: () -> Unit,
  onAlbum: () -> Unit,
  modifier: Modifier,
) {
  val open = viewModel.sheetOpen
  Column(
    modifier
      .fillMaxWidth()
      .then(if (open) Modifier.fillMaxHeight(0.62f) else Modifier)
      .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
      .pointerInput(Unit) {
        // drag up opens the preview, drag down closes it
        var dy = 0f
        detectVerticalDragGestures(
          onDragStart = { dy = 0f },
          onDragEnd = { if (dy < -40) viewModel.sheetOpen = true else if (dy > 40) viewModel.sheetOpen = false },
          onVerticalDrag = { _, d -> dy += d },
        )
      }
      .navigationBarsPadding()
      .padding(horizontal = 16.dp, vertical = 8.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Box(Modifier.align(Alignment.CenterHorizontally).size(36.dp, 4.dp).background(MaterialTheme.colorScheme.outlineVariant, CircleShape).clickable { viewModel.sheetOpen = !open })
    Row(verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text(bubble.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
          "${bubble.size} Medien" + (if (bubble.videos > 0) ", davon ${bubble.videos} Videos" else "") +
            (if (bubble.children.isNotEmpty()) " · ${bubble.children.size} Unter-Bubbles" else ""),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      IconButton(onClick = onRename) { Icon(Icons.Filled.Edit, "Umbenennen") }
      IconButton(onClick = onAlbum) { Icon(Icons.Filled.CreateNewFolder, "Als Album oder Ordner speichern") }
      if (bubble.children.isNotEmpty()) {
        IconButton(onClick = { viewModel.enter(bubble) }) { Icon(Icons.Filled.ZoomIn, "Unter-Bubbles zeigen") }
      }
    }
    if (bubble.tags.isNotEmpty()) {
      FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        bubble.tags.forEach { AssistChip(onClick = {}, label = { Text(it, style = MaterialTheme.typography.labelSmall) }) }
      }
    }
    if (!open) {
      Text("Hochziehen für die Bilder", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else if (members.isNotEmpty()) {
      val pager = rememberPagerState(initialPage = viewModel.page.coerceIn(0, members.lastIndex)) { members.size }
      LaunchedEffect(pager, bubble.key) { snapshotFlow { pager.currentPage }.collect { viewModel.page = it } }
      HorizontalPager(state = pager, modifier = Modifier.fillMaxWidth().weight(1f), pageSpacing = 8.dp, key = { members[it].id }) { i ->
        AsyncImage(
          model = MediaThumb(members[i].uri, 1024),
          imageLoader = loader,
          contentDescription = members[i].name,
          contentScale = ContentScale.Fit,
          modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).background(Color.Black).clickable { onOpen(members, i) },
        )
      }
      Text("${pager.currentPage + 1} / ${members.size}", style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.CenterHorizontally))
    }
  }
}

@Composable
private fun RenameDialog(bubble: Bubble, onDismiss: () -> Unit, onSave: (String) -> Unit) {
  var name by remember { mutableStateOf(bubble.name ?: bubble.suggested ?: "") }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Bubble benennen") },
    text = { OutlinedTextField(name, { name = it.take(60) }, singleLine = true, label = { Text("Name") }) },
    confirmButton = { TextButton(onClick = { onSave(name) }) { Text("Speichern") } },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
  )
}

@Composable
private fun AlbumDialog(bubble: Bubble, onDismiss: () -> Unit, onCreate: (String, Boolean) -> Unit) {
  var name by remember { mutableStateOf(bubble.name ?: bubble.suggested ?: "") }
  var folder by remember { mutableStateOf(false) }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Album aus dieser Bubble") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(name, { name = it.take(60) }, singleLine = true, label = { Text("Name") })
        Text("Neue Fotos, die so aussehen, kommen automatisch dazu.", style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { folder = !folder }) {
          androidx.compose.material3.Checkbox(checked = folder, onCheckedChange = { folder = it })
          Column {
            Text("Als echten Ordner", style = MaterialTheme.typography.bodyMedium)
            Text(
              "Die Dateien werden nach Pictures/${name.trim().ifEmpty { "…" }}/ verschoben und sind so auch in anderen Apps ein Ordner.",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
    },
    confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onCreate(name.trim(), folder) }) { Text("Anlegen") } },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
  )
}
