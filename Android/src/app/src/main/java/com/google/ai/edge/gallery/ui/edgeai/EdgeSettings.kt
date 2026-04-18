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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ai.edge.gallery.ui.theme.appFontFamily

@Composable
fun EdgeSettings(onBack: () -> Unit) {
  var temperature by remember { mutableFloatStateOf(0.7f) }
  var topP by remember { mutableFloatStateOf(0.9f) }
  var maxTokens by remember { mutableFloatStateOf(512f) }
  var streamTokens by remember { mutableStateOf(true) }
  var keepWarm by remember { mutableStateOf(false) }
  var offlineOnly by remember { mutableStateOf(true) }
  var encryptHistory by remember { mutableStateOf(false) }

  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(EdgeBg)
      .windowInsetsPadding(WindowInsets.statusBars)
      .windowInsetsPadding(WindowInsets.navigationBars)
  ) {
    Column(modifier = Modifier.fillMaxSize()) {
      // Header
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .height(56.dp)
          .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        IconButton(onClick = onBack) {
          Icon(
            imageVector = Icons.Default.ArrowBack,
            contentDescription = "Back",
            tint = EdgeTextDim,
          )
        }
        Text(
          text = "Settings",
          color = EdgeText,
          fontSize = 18.sp,
          fontFamily = appFontFamily,
          fontWeight = FontWeight.Bold,
        )
      }

      Column(
        modifier = Modifier
          .weight(1f)
          .verticalScroll(rememberScrollState())
          .padding(horizontal = 16.dp),
      ) {
        // ── Generation ──────────────────────────────────────────────────────
        SectionHeader("Generation")

        SettingsCard {
          SliderRow(
            label = "Temperature",
            value = temperature,
            valueRange = 0f..2f,
            displayValue = "%.2f".format(temperature),
            onValueChange = { temperature = it },
          )

          SettingsDivider()

          SliderRow(
            label = "Top-p",
            value = topP,
            valueRange = 0f..1f,
            displayValue = "%.2f".format(topP),
            onValueChange = { topP = it },
          )

          SettingsDivider()

          SliderRow(
            label = "Max tokens",
            value = maxTokens,
            valueRange = 64f..4096f,
            displayValue = maxTokens.toInt().toString(),
            onValueChange = { maxTokens = it },
          )
        }

        Spacer(Modifier.height(20.dp))

        // ── Runtime ────────────────────────────────────────────────────────
        SectionHeader("Runtime")

        SettingsCard {
          PickerRow(
            label = "Accelerator",
            value = "GPU",
          )

          SettingsDivider()

          ToggleRow(
            label = "Stream tokens",
            subLabel = "Show responses as they generate",
            checked = streamTokens,
            onCheckedChange = { streamTokens = it },
          )

          SettingsDivider()

          ToggleRow(
            label = "Keep warm",
            subLabel = "Keep model loaded between chats",
            checked = keepWarm,
            onCheckedChange = { keepWarm = it },
          )
        }

        Spacer(Modifier.height(20.dp))

        // ── Privacy ────────────────────────────────────────────────────────
        SectionHeader("Privacy")

        SettingsCard {
          ToggleRow(
            label = "Offline only",
            subLabel = "Disable all network access (locked)",
            checked = offlineOnly,
            onCheckedChange = { /* locked */ },
            locked = true,
          )

          SettingsDivider()

          ToggleRow(
            label = "Encrypt history",
            subLabel = "AES-256 encryption for stored chats",
            checked = encryptHistory,
            onCheckedChange = { encryptHistory = it },
          )
        }

        Spacer(Modifier.height(32.dp))

        // Footer
        Text(
          text = "edge ai · v1.0.3 · build 241",
          color = EdgeTextMute,
          fontSize = 11.sp,
          fontFamily = FontFamily.Monospace,
          modifier = Modifier.align(Alignment.CenterHorizontally),
        )

        Spacer(Modifier.height(24.dp))
      }
    }
  }
}

@Composable
private fun SectionHeader(text: String) {
  Text(
    text = text.uppercase(),
    color = EdgeTextMute,
    fontSize = 10.sp,
    fontFamily = FontFamily.Monospace,
    letterSpacing = 2.sp,
    modifier = Modifier.padding(bottom = 8.dp, top = 4.dp),
  )
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(EdgeRadius))
      .background(EdgeSurface)
      .border(1.dp, EdgeBorderStrong, RoundedCornerShape(EdgeRadius))
      .padding(horizontal = 16.dp, vertical = 4.dp),
  ) {
    content()
  }
}

@Composable
private fun SettingsDivider() {
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .height(1.dp)
      .background(EdgeBorderStrong)
  )
}

@Composable
private fun SliderRow(
  label: String,
  value: Float,
  valueRange: ClosedFloatingPointRange<Float>,
  displayValue: String,
  onValueChange: (Float) -> Unit,
) {
  Column(modifier = Modifier.padding(vertical = 12.dp)) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = label,
        color = EdgeText,
        fontSize = 14.sp,
        fontFamily = appFontFamily,
      )
      Text(
        text = displayValue,
        color = EdgeAccent,
        fontSize = 13.sp,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold,
      )
    }
    Slider(
      value = value,
      onValueChange = onValueChange,
      valueRange = valueRange,
      modifier = Modifier.fillMaxWidth(),
      colors = SliderDefaults.colors(
        thumbColor = EdgeAccent,
        activeTrackColor = EdgeAccent,
        inactiveTrackColor = EdgeSurface2,
      ),
    )
  }
}

@Composable
private fun ToggleRow(
  label: String,
  subLabel: String,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
  locked: Boolean = false,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = 14.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = label,
        color = if (locked) EdgeTextDim else EdgeText,
        fontSize = 14.sp,
        fontFamily = appFontFamily,
      )
      Text(
        text = subLabel,
        color = EdgeTextMute,
        fontSize = 12.sp,
        fontFamily = appFontFamily,
      )
    }
    Switch(
      checked = checked,
      onCheckedChange = if (locked) null else onCheckedChange,
      enabled = !locked,
      colors = SwitchDefaults.colors(
        checkedThumbColor = Color.Black,
        checkedTrackColor = EdgeAccent,
        uncheckedThumbColor = EdgeTextDim,
        uncheckedTrackColor = EdgeSurface2,
        disabledCheckedTrackColor = EdgeAccent.copy(alpha = 0.5f),
        disabledCheckedThumbColor = Color.Black,
      ),
    )
  }
}

@Composable
private fun PickerRow(
  label: String,
  value: String,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = 14.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = label,
      color = EdgeText,
      fontSize = 14.sp,
      fontFamily = appFontFamily,
      modifier = Modifier.weight(1f),
    )
    Box(
      modifier = Modifier
        .clip(RoundedCornerShape(6.dp))
        .background(EdgeSurface2)
        .border(1.dp, EdgeBorderStrong, RoundedCornerShape(6.dp))
        .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
      Text(
        text = value,
        color = EdgeAccent,
        fontSize = 12.sp,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold,
      )
    }
  }
}
