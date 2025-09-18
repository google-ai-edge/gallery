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

package com.google.ai.edge.gallery.ui.llmsingleturn

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

@Composable
fun SingleSelectButton(
  config: PromptTemplateSingleSelectInputEditor,
  onSelected: (String) -> Unit,
) {
  var showMenu by remember { mutableStateOf(false) }
  var selectedOption by remember { mutableStateOf(config.defaultOption) }

  LaunchedEffect(config) { selectedOption = config.defaultOption }

  Box {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(2.dp),
      modifier =
        Modifier.clip(RoundedCornerShape(8.dp))
          .background(MaterialTheme.colorScheme.secondaryContainer)
          .clickable { showMenu = true }
          .padding(vertical = 4.dp, horizontal = 6.dp)
          .padding(start = 8.dp),
    ) {
      Text("${config.label}: $selectedOption", style = MaterialTheme.typography.labelLarge)
      Icon(Icons.Rounded.ArrowDropDown, contentDescription = null)
    }

    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
      // Options
      for (option in config.options) {
        DropdownMenuItem(
          text = { Text(option) },
          onClick = {
            selectedOption = option
            showMenu = false
            onSelected(option)
          },
        )
      }
    }
  }
}
