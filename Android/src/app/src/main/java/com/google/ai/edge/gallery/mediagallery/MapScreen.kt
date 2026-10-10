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

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.google.ai.edge.gallery.morgenschiss.MapPoint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Formats other than JPEG mix this colour into their position colour. */
private val formatColors =
  linkedMapOf(
    "PNG" to Color(0xFFFF9800),
    "HEIC" to Color(0xFF4CAF50),
    "WebP" to Color(0xFF9C27B0),
    "GIF" to Color(0xFFE91E63),
    "MP4" to Color(0xFFF44336),
    "Video" to Color(0xFFFFEB3B),
    "Andere" to Color(0xFF9E9E9E),
  )

fun formatOf(mime: String?, kind: String?): String {
  val m = mime.orEmpty().lowercase()
  return when {
    "jpeg" in m || "jpg" in m -> "JPEG"
    "png" in m -> "PNG"
    "heic" in m || "heif" in m -> "HEIC"
    "webp" in m -> "WebP"
    "gif" in m -> "GIF"
    "mp4" in m -> "MP4"
    m.startsWith("video") || kind == "video" -> "Video"
    else -> "Andere"
  }
}

/**
 * Colour from the position in the cube (x -> red, y -> green, z -> blue), so neighbours share a
 * hue and clusters stand out. Non-JPEG points take the average with their format colour.
 */
fun pointColor(x: Float, y: Float, z: Float, format: String): Color {
  fun channel(v: Float) = 0.2f + 0.8f * ((v.coerceIn(-1f, 1f) + 1f) / 2f)
  val pos = Color(channel(x), channel(y), channel(z))
  val fmt = formatColors[format] ?: return pos
  return Color((pos.red + fmt.red) / 2, (pos.green + fmt.green) / 2, (pos.blue + fmt.blue) / 2)
}

/** Precomputed, so a frame only rotates and projects. */
private class Cloud(points: List<Pair<MapPoint, MediaItem?>>) {
  val n = points.size
  val xs = FloatArray(n) { points[it].first.x }
  val ys = FloatArray(n) { points[it].first.y }
  val zs = FloatArray(n) { points[it].first.z }
  val formats = Array(n) { formatOf(points[it].first.mime, points[it].first.kind) }
  val colors = Array(n) { pointColor(xs[it], ys[it], zs[it], formats[it]) }
  /** Radius factor 0..1 by file size (square root, so huge videos do not cover everything). */
  val radius: FloatArray
  val items = Array(n) { points[it].second }
  /** Formats besides JPEG, with their share, for the legend. */
  val otherFormats = formats.filter { it != "JPEG" }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }

  init {
    val max = points.maxOfOrNull { it.first.size ?: 0L }?.coerceAtLeast(1L) ?: 1L
    radius = FloatArray(n) { sqrt((points[it].first.size ?: 0L).toFloat() / max) }
  }
}

private val cubeCorners =
  listOf(-1f, 1f).flatMap { x -> listOf(-1f, 1f).flatMap { y -> listOf(-1f, 1f).map { z -> Triple(x, y, z) } } }
private val cubeEdges =
  cubeCorners.indices.flatMap { a ->
    cubeCorners.indices.filter { b -> b > a }.filter { b ->
      val p = cubeCorners[a]
      val q = cubeCorners[b]
      listOf(p.first != q.first, p.second != q.second, p.third != q.third).count { it } == 1
    }.map { a to it }
  }

private val dateFormat = SimpleDateFormat("d. MMM yyyy", Locale.GERMANY)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MapScreen(viewModel: AnalysisViewModel, onBack: () -> Unit, onOpen: (MediaItem) -> Unit) {
  val state by viewModel.map.collectAsState()
  val camera = viewModel.camera
  LaunchedEffect(Unit) { if (state !is Loadable.Done) viewModel.loadMap() }
  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("Analyse 3D") },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") } },
        actions = { IconButton(onClick = { camera.reset() }) { Icon(Icons.Filled.CenterFocusStrong, "Ansicht zurücksetzen") } },
      )
    }
  ) { padding ->
    Box(Modifier.fillMaxSize().padding(padding)) {
      when (val s = state) {
        is Loadable.Done -> {
          val cloud = remember(s.value) { Cloud(s.value) }
          Column {
            Text(
              "Ähnliche Medien liegen nah beieinander, Farbe = Lage im Würfel, Größe = Dateigröße. Ein Finger dreht, zwei Finger zoomen und verschieben, Tippen wählt aus.",
              style = MaterialTheme.typography.bodySmall,
              modifier = Modifier.padding(horizontal = 16.dp),
            )
            if (cloud.otherFormats.isNotEmpty()) {
              FlowRow(Modifier.padding(16.dp, 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Andere Formate mischen ein:", style = MaterialTheme.typography.labelSmall)
                cloud.otherFormats.forEach { (f, count) ->
                  Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).background(formatColors.getValue(f), CircleShape))
                    Spacer(Modifier.width(4.dp))
                    Text("$f ($count)", style = MaterialTheme.typography.labelSmall)
                  }
                }
              }
            }
            Box(Modifier.fillMaxSize()) {
              CubeCanvas(cloud, camera)
              val sel = camera.selected.takeIf { it in 0 until cloud.n }
              sel?.let { k ->
                cloud.items[k]?.let { item ->
                  SelectionCard(item, onOpen = { onOpen(item) }, onClose = { camera.selected = -1 }, modifier = Modifier.align(Alignment.BottomCenter))
                }
              }
            }
          }
        }
        is Loadable.Error -> Text(s.message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
        else -> CircularProgressIndicator(Modifier.align(Alignment.Center))
      }
    }
  }
}

@Composable
private fun SelectionCard(item: MediaItem, onOpen: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
  Row(
    modifier
      .fillMaxWidth()
      .navigationBarsPadding()
      .padding(12.dp)
      .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(16.dp))
      .clickable(onClick = onOpen)
      .padding(10.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    AsyncImage(
      model = MediaThumb(item.uri),
      imageLoader = rememberGalleryImageLoader(),
      contentDescription = item.name,
      contentScale = ContentScale.Crop,
      modifier = Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)),
    )
    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
      Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
      Text("${dateFormat.format(Date(item.takenAt))} · ${item.bucketName}", style = MaterialTheme.typography.bodySmall)
    }
    Button(onClick = onOpen) { Text("Öffnen") }
    IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Auswahl aufheben") }
  }
}

@Composable
private fun CubeCanvas(cloud: Cloud, camera: MapCamera) {
  // projected screen positions of the last frame, for hit tests
  val px = remember(cloud) { FloatArray(cloud.n) }
  val py = remember(cloud) { FloatArray(cloud.n) }
  val pr = remember(cloud) { FloatArray(cloud.n) }
  val depth = remember(cloud) { FloatArray(cloud.n) }
  val order = remember(cloud) { IntArray(cloud.n) { it } }
  Canvas(
    Modifier.fillMaxSize()
      .background(Color(0xFF101418))
      .pointerInput(cloud) {
        // one finger rotates (a full width = half a turn), two fingers zoom and move
        awaitEachGesture {
          awaitFirstDown(requireUnconsumed = false)
          do {
            val event = awaitPointerEvent()
            val fingers = event.changes.count { it.pressed }
            if (fingers >= 2) {
              val zoom = event.calculateZoom()
              val pan = event.calculatePan()
              val centroid = event.calculateCentroid()
              val newZoom = (camera.zoom * zoom).coerceIn(0.5f, 12f)
              // zoom towards the fingers, not the middle of the cube
              val cx = size.width / 2 + camera.panX
              val cy = size.height / 2 + camera.panY
              val f = newZoom / camera.zoom
              camera.panX += (centroid.x - cx) * (1 - f) + pan.x
              camera.panY += (centroid.y - cy) * (1 - f) + pan.y
              camera.zoom = newZoom
            } else if (fingers == 1) {
              val pan = event.calculatePan()
              camera.yaw += pan.x / size.width * PI.toFloat()
              camera.pitch = (camera.pitch - pan.y / size.height * PI.toFloat()).coerceIn(-1.5f, 1.5f)
            }
            event.changes.forEach { if (it.positionChanged()) it.consume() }
          } while (event.changes.any { it.pressed })
        }
      }
      .pointerInput(cloud) {
        detectTapGestures(
          onDoubleTap = { camera.reset() },
          onTap = { tap ->
            // the front-most point near the finger; a generous radius so small balls stay tappable
            val reach = 28.dp.toPx()
            var best = -1
            for (i in order.indices.reversed()) {
              val k = order[i]
              if (cloud.items[k] == null) continue
              val dx = px[k] - tap.x
              val dy = py[k] - tap.y
              val r = maxOf(pr[k], reach)
              if (dx * dx + dy * dy <= r * r) {
                best = k
                break
              }
            }
            camera.selected = best
          },
        )
      }
  ) {
    val cyaw = cos(camera.yaw)
    val syaw = sin(camera.yaw)
    val cp = cos(camera.pitch)
    val sp = sin(camera.pitch)
    val scale = minOf(size.width, size.height) * 0.36f * camera.zoom
    val cx0 = size.width / 2 + camera.panX
    val cy0 = size.height / 2 + camera.panY
    val cameraDist = 4f
    fun project(x: Float, y: Float, z: Float, out: (Float, Float, Float) -> Unit) {
      val x1 = x * cyaw + z * syaw
      val z1 = -x * syaw + z * cyaw
      val y2 = y * cp - z1 * sp
      val z2 = y * sp + z1 * cp
      val f = cameraDist / (cameraDist - z2)
      out(cx0 + x1 * f * scale, cy0 - y2 * f * scale, z2)
    }
    for ((a, b) in cubeEdges) {
      var ax = 0f; var ay = 0f; var bx = 0f; var by = 0f
      cubeCorners[a].let { (x, y, z) -> project(x, y, z) { sx, sy, _ -> ax = sx; ay = sy } }
      cubeCorners[b].let { (x, y, z) -> project(x, y, z) { sx, sy, _ -> bx = sx; by = sy } }
      drawLine(Color.White.copy(alpha = 0.25f), Offset(ax, ay), Offset(bx, by), strokeWidth = 2f)
    }
    // many points need smaller balls, or the cloud turns into one blob; zooming in shows detail
    val density = minOf(1f, sqrt(400f / cloud.n.coerceAtLeast(1)))
    val base = 1.5.dp.toPx() + 1.5.dp.toPx() * density
    val extra = 12.dp.toPx() * density
    for (i in 0 until cloud.n) {
      project(cloud.xs[i], cloud.ys[i], cloud.zs[i]) { sx, sy, d ->
        px[i] = sx
        py[i] = sy
        depth[i] = d
        pr[i] = (base + cloud.radius[i] * extra) * (cameraDist / (cameraDist - d)) * sqrt(camera.zoom)
      }
    }
    // painter's algorithm: far points first
    val sorted = order.sortedBy { depth[it] }
    sorted.forEachIndexed { i, k -> order[i] = k }
    for (k in order) {
      drawCircle(cloud.colors[k].copy(alpha = 0.9f), pr[k], Offset(px[k], py[k]))
    }
    camera.selected.takeIf { it in 0 until cloud.n }?.let { k ->
      drawCircle(Color.White, pr[k] + 4.dp.toPx(), Offset(px[k], py[k]), style = Stroke(width = 2.dp.toPx()))
    }
  }
}
