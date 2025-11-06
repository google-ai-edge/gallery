/*
 * Copyright 2025 Google LLC
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

package com.neuralforge.mobile.ui.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

val Deployed_code: ImageVector
  get() {
    if (internal_Deployed_code != null) {
      return internal_Deployed_code!!
    }
    internal_Deployed_code =
      ImageVector.Builder(
          name = "Deployed_code",
          defaultWidth = 24.dp,
          defaultHeight = 24.dp,
          viewportWidth = 960f,
          viewportHeight = 960f,
        )
        .apply {
          path(
            fill = SolidColor(Color.Black),
            fillAlpha = 1.0f,
            stroke = null,
            strokeAlpha = 1.0f,
            strokeLineWidth = 1.0f,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Miter,
            strokeLineMiter = 1.0f,
            pathFillType = PathFillType.NonZero,
          ) {
            moveTo(440f, 777f)
            verticalLineToRelative(-274f)
            lineTo(200f, 364f)
            verticalLineToRelative(274f)
            close()
            moveToRelative(80f, 0f)
            lineToRelative(240f, -139f)
            verticalLineToRelative(-274f)
            lineTo(520f, 503f)
            close()
            moveToRelative(-40f, -343f)
            lineToRelative(237f, -137f)
            lineToRelative(-237f, -137f)
            lineToRelative(-237f, 137f)
            close()
            moveTo(160f, 708f)
            quadToRelative(-19f, -11f, -29.5f, -29f)
            reflectiveQuadTo(120f, 639f)
            verticalLineToRelative(-318f)
            quadToRelative(0f, -22f, 10.5f, -40f)
            reflectiveQuadToRelative(29.5f, -29f)
            lineToRelative(280f, -161f)
            quadToRelative(19f, -11f, 40f, -11f)
            reflectiveQuadToRelative(40f, 11f)
            lineToRelative(280f, 161f)
            quadToRelative(19f, 11f, 29.5f, 29f)
            reflectiveQuadToRelative(10.5f, 40f)
            verticalLineToRelative(318f)
            quadToRelative(0f, 22f, -10.5f, 40f)
            reflectiveQuadTo(800f, 708f)
            lineTo(520f, 869f)
            quadToRelative(-19f, 11f, -40f, 11f)
            reflectiveQuadToRelative(-40f, -11f)
            close()
            moveToRelative(320f, -228f)
          }
        }
        .build()
    return internal_Deployed_code!!
  }

private var internal_Deployed_code: ImageVector? = null
