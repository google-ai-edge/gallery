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

package com.google.ai.edge.gallery.ui.edgeai

import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ai.edge.gallery.ui.theme.appFontFamily

@Composable
fun EdgeVoiceMode(onDismiss: () -> Unit) {
  val infiniteTransition = rememberInfiniteTransition(label = "voiceRings")

  // 5 rings with staggered animations
  val ring1Scale by infiniteTransition.animateFloat(
    initialValue = 1f, targetValue = 1.25f,
    animationSpec = infiniteRepeatable(tween(1200, easing = EaseInOut), RepeatMode.Reverse),
    label = "r1",
  )
  val ring2Scale by infiniteTransition.animateFloat(
    initialValue = 1f, targetValue = 1.4f,
    animationSpec = infiniteRepeatable(tween(1400, delayMillis = 100, easing = EaseInOut), RepeatMode.Reverse),
    label = "r2",
  )
  val ring3Scale by infiniteTransition.animateFloat(
    initialValue = 1f, targetValue = 1.55f,
    animationSpec = infiniteRepeatable(tween(1600, delayMillis = 200, easing = EaseInOut), RepeatMode.Reverse),
    label = "r3",
  )
  val ring4Scale by infiniteTransition.animateFloat(
    initialValue = 1f, targetValue = 1.7f,
    animationSpec = infiniteRepeatable(tween(1800, delayMillis = 300, easing = EaseInOut), RepeatMode.Reverse),
    label = "r4",
  )
  val ring5Scale by infiniteTransition.animateFloat(
    initialValue = 1f, targetValue = 1.85f,
    animationSpec = infiniteRepeatable(tween(2000, delayMillis = 400, easing = EaseInOut), RepeatMode.Reverse),
    label = "r5",
  )

  // Waveform bars animation
  val waveAlpha by infiniteTransition.animateFloat(
    initialValue = 0.4f, targetValue = 1f,
    animationSpec = infiniteRepeatable(tween(600, easing = LinearEasing), RepeatMode.Reverse),
    label = "wave",
  )

  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(EdgeBg)
      .windowInsetsPadding(WindowInsets.statusBars)
      .windowInsetsPadding(WindowInsets.navigationBars),
    contentAlignment = Alignment.Center,
  ) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      modifier = Modifier.fillMaxSize(),
    ) {
      Spacer(Modifier.height(24.dp))

      // LISTENING chip
      Box(
        modifier = Modifier
          .clip(RoundedCornerShape(50.dp))
          .background(EdgeAccentSoft)
          .border(1.dp, EdgeAccentBorder, RoundedCornerShape(50.dp))
          .padding(horizontal = 18.dp, vertical = 8.dp),
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Box(
            modifier = Modifier
              .size(6.dp)
              .clip(CircleShape)
              .background(EdgeAccent)
          )
          Text(
            text = "LISTENING",
            color = EdgeAccent,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 2.sp,
            fontWeight = FontWeight.Bold,
          )
        }
      }

      Spacer(Modifier.weight(1f))

      // Animated concentric rings around central orb
      Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(260.dp),
      ) {
        // Rings (outermost first)
        Box(
          modifier = Modifier
            .size(220.dp)
            .scale(ring5Scale)
            .clip(CircleShape)
            .border(1.dp, EdgeAccent.copy(alpha = 0.05f), CircleShape),
        )
        Box(
          modifier = Modifier
            .size(190.dp)
            .scale(ring4Scale)
            .clip(CircleShape)
            .border(1.dp, EdgeAccent.copy(alpha = 0.09f), CircleShape),
        )
        Box(
          modifier = Modifier
            .size(160.dp)
            .scale(ring3Scale)
            .clip(CircleShape)
            .border(1.dp, EdgeAccent.copy(alpha = 0.14f), CircleShape),
        )
        Box(
          modifier = Modifier
            .size(130.dp)
            .scale(ring2Scale)
            .clip(CircleShape)
            .border(1.5.dp, EdgeAccent.copy(alpha = 0.22f), CircleShape),
        )
        Box(
          modifier = Modifier
            .size(100.dp)
            .scale(ring1Scale)
            .clip(CircleShape)
            .border(1.5.dp, EdgeAccent.copy(alpha = 0.35f), CircleShape),
        )

        // Central orb
        Box(
          modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(EdgeAccentSoft)
            .border(2.dp, EdgeAccent, CircleShape),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            imageVector = Icons.Default.Mic,
            contentDescription = "Mic",
            tint = EdgeAccent,
            modifier = Modifier.size(28.dp),
          )
        }
      }

      Spacer(Modifier.height(32.dp))

      // Transcript area
      Text(
        text = "Listening…",
        color = EdgeTextDim,
        fontSize = 15.sp,
        fontFamily = appFontFamily,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = 40.dp),
      )

      Spacer(Modifier.weight(1f))

      // Bottom controls
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 48.dp, vertical = 32.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        // Pause
        Box(
          modifier = Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(EdgeSurface)
            .border(1.dp, EdgeBorderStrong, CircleShape)
            .clickable { },
          contentAlignment = Alignment.Center,
        ) {
          Text(text = "⏸", fontSize = 20.sp)
        }

        // STOP (larger)
        Box(
          modifier = Modifier
            .size(68.dp)
            .clip(CircleShape)
            .background(EdgeAccent)
            .clickable(onClick = onDismiss),
          contentAlignment = Alignment.Center,
        ) {
          Text(
            text = "STOP",
            color = Color.Black,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 1.sp,
          )
        }

        // Waveform (decorative)
        Row(
          modifier = Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(EdgeSurface)
            .border(1.dp, EdgeBorderStrong, CircleShape),
          horizontalArrangement = Arrangement.Center,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          repeat(5) { i ->
            val barHeight = (8 + (i % 3) * 6).dp
            Box(
              modifier = Modifier
                .size(width = 3.dp, height = barHeight)
                .background(EdgeAccent.copy(alpha = waveAlpha * (0.4f + i * 0.12f))),
            )
            if (i < 4) Spacer(Modifier.size(2.dp))
          }
        }
      }
    }
  }
}
