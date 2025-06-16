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

val Widgets: ImageVector
  get() {
    if (_Widgets != null) return _Widgets!!

    _Widgets =
      ImageVector.Builder(
          name = "Widgets",
          defaultWidth = 24.dp,
          defaultHeight = 24.dp,
          viewportWidth = 960f,
          viewportHeight = 960f,
        )
        .apply {
          path(fill = SolidColor(Color(0xFF000000))) {
            moveTo(666f, 520f)
            lineTo(440f, 294f)
            lineToRelative(226f, -226f)
            lineToRelative(226f, 226f)
            close()
            moveToRelative(-546f, -80f)
            verticalLineToRelative(-320f)
            horizontalLineToRelative(320f)
            verticalLineToRelative(320f)
            close()
            moveToRelative(400f, 400f)
            verticalLineToRelative(-320f)
            horizontalLineToRelative(320f)
            verticalLineToRelative(320f)
            close()
            moveToRelative(-400f, 0f)
            verticalLineToRelative(-320f)
            horizontalLineToRelative(320f)
            verticalLineToRelative(320f)
            close()
            moveToRelative(80f, -480f)
            horizontalLineToRelative(160f)
            verticalLineToRelative(-160f)
            horizontalLineTo(200f)
            close()
            moveToRelative(467f, 48f)
            lineToRelative(113f, -113f)
            lineToRelative(-113f, -113f)
            lineToRelative(-113f, 113f)
            close()
            moveToRelative(-67f, 352f)
            horizontalLineToRelative(160f)
            verticalLineToRelative(-160f)
            horizontalLineTo(600f)
            close()
            moveToRelative(-400f, 0f)
            horizontalLineToRelative(160f)
            verticalLineToRelative(-160f)
            horizontalLineTo(200f)
            close()
            moveToRelative(400f, -160f)
          }
        }
        .build()

    return _Widgets!!
  }

private var _Widgets: ImageVector? = null
