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

val Forum: ImageVector
  get() {
    if (_Forum != null) return _Forum!!

    _Forum =
      ImageVector.Builder(
          name = "Forum",
          defaultWidth = 24.dp,
          defaultHeight = 24.dp,
          viewportWidth = 960f,
          viewportHeight = 960f,
        )
        .apply {
          path(fill = SolidColor(Color(0xFF000000))) {
            moveTo(280f, 720f)
            quadToRelative(-17f, 0f, -28.5f, -11.5f)
            reflectiveQuadTo(240f, 680f)
            verticalLineToRelative(-80f)
            horizontalLineToRelative(520f)
            verticalLineToRelative(-360f)
            horizontalLineToRelative(80f)
            quadToRelative(17f, 0f, 28.5f, 11.5f)
            reflectiveQuadTo(880f, 280f)
            verticalLineToRelative(600f)
            lineTo(720f, 720f)
            close()
            moveTo(80f, 680f)
            verticalLineToRelative(-560f)
            quadToRelative(0f, -17f, 11.5f, -28.5f)
            reflectiveQuadTo(120f, 80f)
            horizontalLineToRelative(520f)
            quadToRelative(17f, 0f, 28.5f, 11.5f)
            reflectiveQuadTo(680f, 120f)
            verticalLineToRelative(360f)
            quadToRelative(0f, 17f, -11.5f, 28.5f)
            reflectiveQuadTo(640f, 520f)
            horizontalLineTo(240f)
            close()
            moveToRelative(520f, -240f)
            verticalLineToRelative(-280f)
            horizontalLineTo(160f)
            verticalLineToRelative(280f)
            close()
            moveToRelative(-440f, 0f)
            verticalLineToRelative(-280f)
            close()
          }
        }
        .build()

    return _Forum!!
  }

private var _Forum: ImageVector? = null
