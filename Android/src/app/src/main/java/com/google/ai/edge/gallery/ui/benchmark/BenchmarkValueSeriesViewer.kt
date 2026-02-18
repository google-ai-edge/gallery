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

package com.google.ai.edge.gallery.ui.benchmark

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect.Companion.dashPathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.proto.ValueSeries
import com.google.ai.edge.gallery.ui.theme.customColors
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BenchmarkValueSeriesViewer(title: String, valueSeries: ValueSeries, onDismiss: () -> Unit) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = MaterialTheme.colorScheme.surface,
  ) {
    Column(
      modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      // Title.
      Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
      )

      val values = valueSeries.valueList
      if (values.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
          val lineColor = MaterialTheme.colorScheme.outline
          val dotBgColor = MaterialTheme.colorScheme.surface
          val dotBorderColor = MaterialTheme.colorScheme.outline
          val tappedLineColor = MaterialTheme.customColors.linkColor
          var tappedValue by remember { mutableStateOf<Double?>(null) }

          // Value for tapped point.
          Text(
            if (tappedValue == null) {
              stringResource(R.string.tap_to_see_value)
            } else {
              "Value: ${String.format(Locale.getDefault(), "%.2f", tappedValue)}"
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )

          // Sparkline.
          val verticalPaddingFactor = 0.2f
          val min = valueSeries.min
          val max = valueSeries.max
          val range = max - min
          val effectiveMin = min - (range * verticalPaddingFactor)
          val effectiveMax = max + (range * verticalPaddingFactor)
          val scaledYRange = effectiveMax - effectiveMin
          Canvas(
            modifier =
              Modifier.clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .fillMaxWidth()
                .height(80.dp)
                .pointerInput(values) {
                  awaitPointerEventScope {
                    while (true) {
                      val event = awaitPointerEvent()
                      val position = event.changes.firstOrNull()?.position
                      if (position != null) {
                        // Update on Press and Move events
                        if (
                          event.type == PointerEventType.Press ||
                            event.type == PointerEventType.Move
                        ) {
                          val tappedY = position.y
                          val value = effectiveMin + (1f - (tappedY / size.height)) * scaledYRange
                          tappedValue = value
                          // Consume the event to prevent it from being propagated to parent
                          event.changes.forEach { it.consume() }
                        }
                      }
                    }
                  }
                }
          ) {
            val horizontalPaddingDp = 12.dp
            val horizontalPaddingPx = horizontalPaddingDp.toPx()
            val width = size.width - horizontalPaddingPx * 2
            val height = size.height

            val xStep = if (values.size > 1) width / (values.size - 1) else 0f

            val points =
              values.mapIndexed { index, value ->
                val x = index * xStep + horizontalPaddingPx
                val y = height - ((value - effectiveMin) / scaledYRange) * height
                Offset(x, y.toFloat())
              }

            // Draw lines connecting the points
            for (i in 0 until points.size - 1) {
              drawLine(
                color = lineColor,
                start = points[i],
                end = points[i + 1],
                strokeWidth = 2.dp.toPx(),
              )
            }

            // Draw dots for each value
            val dotRadius = 4.dp.toPx()
            val dotBorderWidth = 2.dp.toPx()
            for (offset in points) {
              // background
              drawCircle(color = dotBgColor, radius = dotRadius, center = offset)
              // border
              drawCircle(
                color = dotBorderColor,
                radius = dotRadius,
                center = offset,
                style = Stroke(width = dotBorderWidth),
              )
            }

            // Draw dashed line for tapped value
            if (tappedValue != null) {
              val y = size.height - ((tappedValue!! - effectiveMin) / scaledYRange) * size.height
              val start = Offset(0f, y.toFloat())
              val end = Offset(size.width, y.toFloat())
              val dashIntervals = floatArrayOf(10f, 10f) // 10px on, 10px off
              drawLine(
                color = tappedLineColor,
                start = start,
                end = end,
                strokeWidth = 1.dp.toPx(),
                pathEffect = dashPathEffect(dashIntervals, 0f),
              )
            }
          }
        }
      }

      // Stats.
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth(),
      ) {
        StatCell(key = "avg", value = valueSeries.avg)
        StatCell(key = "median", value = valueSeries.medium)
        StatCell(key = "min", value = valueSeries.min)
        StatCell(key = "max", value = valueSeries.max)
      }
    }
  }
}

@Composable
private fun StatCell(key: String, value: Double) {
  Column() {
    Text(
      String.format(Locale.getDefault(), "%.2f", value),
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      maxLines = 1,
      autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = 12.sp, stepSize = 1.sp),
    )
    Text(
      key,
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
    )
  }
}
