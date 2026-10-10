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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.morgenschiss.MapPoint
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Color per format family; the legend shows the ones present. */
private val formatColors =
  linkedMapOf(
    "JPEG" to Color(0xFF4FC3F7),
    "PNG" to Color(0xFFFFB74D),
    "HEIC" to Color(0xFF81C784),
    "WebP" to Color(0xFFBA68C8),
    "GIF" to Color(0xFFF06292),
    "MP4" to Color(0xFFE57373),
    "Video" to Color(0xFFFFF176),
    "Andere" to Color(0xFF90A4AE),
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

/** Precomputed, so a frame only rotates and projects. */
private class Cloud(points: List<Pair<MapPoint, MediaItem?>>) {
  val n = points.size
  val xs = FloatArray(n) { points[it].first.x }
  val ys = FloatArray(n) { points[it].first.y }
  val zs = FloatArray(n) { points[it].first.z }
  val colors = Array(n) { formatColors.getValue(formatOf(points[it].first.mime, points[it].first.kind)) }
  /** Radius factor 0..1 by file size (square root, so huge videos do not cover everything). */
  val radius: FloatArray
  val items = Array(n) { points[it].second }
  val legend = points.map { formatOf(it.first.mime, it.first.kind) }.distinct()

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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MapScreen(viewModel: AnalysisViewModel, onBack: () -> Unit, onOpen: (MediaItem) -> Unit) {
  val state by viewModel.map.collectAsState()
  LaunchedEffect(Unit) { if (state !is Loadable.Done) viewModel.loadMap() }
  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("Analyse 3D") },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") } },
      )
    }
  ) { padding ->
    Box(Modifier.fillMaxSize().padding(padding)) {
      when (val s = state) {
        is Loadable.Done -> {
          val cloud = remember(s.value) { Cloud(s.value) }
          Column {
            Text(
              "Ähnliche Medien liegen nah beieinander. Größe = Dateigröße, Farbe = Format. Ziehen dreht, zwei Finger zoomen, Tippen öffnet.",
              style = MaterialTheme.typography.bodySmall,
              modifier = Modifier.padding(horizontal = 16.dp),
            )
            FlowRow(Modifier.padding(16.dp, 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
              cloud.legend.forEach { f ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                  Box(Modifier.size(10.dp).background(formatColors.getValue(f), CircleShape))
                  Spacer(Modifier.width(4.dp))
                  Text(f, style = MaterialTheme.typography.labelSmall)
                }
              }
            }
            CubeCanvas(cloud, onOpen)
          }
        }
        is Loadable.Error -> Text(s.message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
        else -> CircularProgressIndicator(Modifier.align(Alignment.Center))
      }
    }
  }
}

@Composable
private fun CubeCanvas(cloud: Cloud, onOpen: (MediaItem) -> Unit) {
  var yaw by remember { mutableFloatStateOf(0.6f) }
  var pitch by remember { mutableFloatStateOf(-0.4f) }
  var zoom by remember { mutableFloatStateOf(1f) }
  // projected screen positions of the last frame, for hit tests
  val px = remember(cloud) { FloatArray(cloud.n) }
  val py = remember(cloud) { FloatArray(cloud.n) }
  val pr = remember(cloud) { FloatArray(cloud.n) }
  val depth = remember(cloud) { FloatArray(cloud.n) }
  val order = remember(cloud) { Array(cloud.n) { it } }
  Canvas(
    Modifier.fillMaxWidth()
      .fillMaxSize()
      .background(Color(0xFF101418))
      .pointerInput(cloud) {
        detectTransformGestures { _, pan, gestureZoom, _ ->
          yaw += pan.x * 0.01f
          pitch = (pitch + pan.y * 0.01f).coerceIn(-1.5f, 1.5f)
          zoom = (zoom * gestureZoom).coerceIn(0.5f, 6f)
        }
      }
      .pointerInput(cloud) {
        detectTapGestures { tap ->
          // front-most point under the finger
          var best = -1
          for (i in order.indices.reversed()) {
            val k = order[i]
            val dx = px[k] - tap.x
            val dy = py[k] - tap.y
            if (dx * dx + dy * dy <= maxOf(pr[k], 24f) * maxOf(pr[k], 24f) && cloud.items[k] != null) {
              best = k
              break
            }
          }
          if (best >= 0) cloud.items[best]?.let(onOpen)
        }
      }
  ) {
    val cy = cos(yaw)
    val sy = sin(yaw)
    val cp = cos(pitch)
    val sp = sin(pitch)
    val scale = minOf(size.width, size.height) * 0.32f * zoom
    val cx0 = size.width / 2
    val cy0 = size.height / 2
    val camera = 4f
    fun project(x: Float, y: Float, z: Float, out: (Float, Float, Float) -> Unit) {
      val x1 = x * cy + z * sy
      val z1 = -x * sy + z * cy
      val y2 = y * cp - z1 * sp
      val z2 = y * sp + z1 * cp
      val f = camera / (camera - z2)
      out(cx0 + x1 * f * scale, cy0 - y2 * f * scale, z2)
    }
    for ((a, b) in cubeEdges) {
      var ax = 0f; var ay = 0f; var bx = 0f; var by = 0f
      cubeCorners[a].let { (x, y, z) -> project(x, y, z) { sx, sy2, _ -> ax = sx; ay = sy2 } }
      cubeCorners[b].let { (x, y, z) -> project(x, y, z) { sx, sy2, _ -> bx = sx; by = sy2 } }
      drawLine(Color.White.copy(alpha = 0.25f), Offset(ax, ay), Offset(bx, by), strokeWidth = 2f)
    }
    // many points need smaller balls, or the cloud turns into one blob
    val density = minOf(1f, sqrt(400f / cloud.n.coerceAtLeast(1)))
    val base = 1.5.dp.toPx() + 1.5.dp.toPx() * density
    val extra = 12.dp.toPx() * density
    for (i in 0 until cloud.n) {
      project(cloud.xs[i], cloud.ys[i], cloud.zs[i]) { sx, sy2, d ->
        px[i] = sx
        py[i] = sy2
        depth[i] = d
        pr[i] = (base + cloud.radius[i] * extra) * (camera / (camera - d)) * sqrt(zoom)
      }
    }
    // painter's algorithm: far points first
    order.sortBy { depth[it] }
    for (k in order) {
      drawCircle(cloud.colors[k].copy(alpha = 0.85f), pr[k], Offset(px[k], py[k]))
    }
  }
}
