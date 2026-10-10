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

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

/** Search field above a grid: searches the current folder unless "Überall" is on. */
@Composable
fun SearchBarRow(
  state: SearchState,
  showEverywhere: Boolean,
  onQuery: (String) -> Unit,
  onEverywhere: (Boolean) -> Unit,
  onClear: () -> Unit,
  focusRequester: FocusRequester? = null,
) {
  val keyboard = LocalSoftwareKeyboardController.current
  Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      OutlinedTextField(
        value = state.query,
        onValueChange = onQuery,
        placeholder = { Text(if (showEverywhere && !state.everywhere) "In diesem Ordner suchen" else "Fotos und Videos suchen") },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
          when {
            state.loading -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            state.query.isNotEmpty() ->
              IconButton(onClick = onClear) { Icon(Icons.Filled.Close, contentDescription = "Suche löschen") }
          }
        },
        singleLine = true,
        shape = RoundedCornerShape(28.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
        modifier = Modifier.weight(1f).let { m -> focusRequester?.let { m.focusRequester(it) } ?: m },
      )
      if (showEverywhere) {
        Spacer(Modifier.width(8.dp))
        FilterChip(selected = state.everywhere, onClick = { onEverywhere(!state.everywhere) }, label = { Text("Überall") })
      }
    }
    state.message?.let {
      Text(
        it,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 4.dp),
      )
    }
  }
}
