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

package com.google.ai.edge.gallery.ui.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

val Mms: ImageVector
  get() {
    if (_Mms != null) return _Mms!!

    _Mms =
      ImageVector.Builder(
          name = "Mms",
          defaultWidth = 24.dp,
          defaultHeight = 24.dp,
          viewportWidth = 960f,
          viewportHeight = 960f,
        )
        .apply {
          path(fill = SolidColor(Color(0xFF000000))) {
            moveTo(240f, 560f)
            horizontalLineToRelative(480f)
            lineTo(570f, 360f)
            lineTo(450f, 520f)
            lineToRelative(-90f, -120f)
            close()
            moveTo(80f, 880f)
            verticalLineToRelative(-720f)
            quadToRelative(0f, -33f, 23.5f, -56.5f)
            reflectiveQuadTo(160f, 80f)
            horizontalLineToRelative(640f)
            quadToRelative(33f, 0f, 56.5f, 23.5f)
            reflectiveQuadTo(880f, 160f)
            verticalLineToRelative(480f)
            quadToRelative(0f, 33f, -23.5f, 56.5f)
            reflectiveQuadTo(800f, 720f)
            horizontalLineTo(240f)
            close()
            moveToRelative(126f, -240f)
            horizontalLineToRelative(594f)
            verticalLineToRelative(-480f)
            horizontalLineTo(160f)
            verticalLineToRelative(525f)
            close()
            moveToRelative(-46f, 0f)
            verticalLineToRelative(-480f)
            close()
          }
        }
        .build()

    return _Mms!!
  }

private var _Mms: ImageVector? = null
