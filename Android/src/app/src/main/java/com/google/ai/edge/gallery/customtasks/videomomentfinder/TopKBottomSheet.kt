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

package com.google.ai.edge.gallery.customtasks.videomomentfinder

import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.common.logButtonClick
import com.google.ai.edge.gallery.ui.common.SMALL_BUTTON_CONTENT_PADDING

/** Allowed bounds for the number of search results returned for a query. */
internal const val MIN_TOP_K = 1
internal const val MAX_TOP_K = 10

/**
 * A bottom sheet that adjusts how many moments a search returns.
 *
 * This is deliberately separate from [ProcessingConfigDialog]: the top-K value only affects how the
 * already-indexed windows are ranked at query time, so changing it re-runs the search instead of
 * re-indexing the video.
 *
 * The slider edits a local copy and only reports it through [onApply], so dismissing the sheet
 * leaves the current value untouched.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopKBottomSheet(initialTopK: Int, onDismiss: () -> Unit, onApply: (Int) -> Unit) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  // Seeded once per open, deliberately not keyed on [initialTopK]: the sheet is created fresh each
  // time it is shown, and keying would snap the slider back mid-drag if the project object changed.
  var topK by remember {
    mutableIntStateOf(if (initialTopK in MIN_TOP_K..MAX_TOP_K) initialTopK else DEFAULT_TOP_K)
  }

  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
    Column(
      modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Text(
        text = stringResource(R.string.videomomentfinder_top_k_sheet_title),
        style = MaterialTheme.typography.titleLarge,
      )
      ConfigSlider(
        labelResId = R.string.videomomentfinder_config_top_k,
        valueLabel = stringResource(R.string.videomomentfinder_config_number_value, topK),
        value = topK,
        valueRange = MIN_TOP_K..MAX_TOP_K,
        onValueChange = { topK = it },
      )
      Button(
        onClick = {
          logButtonClick("videomomentfinder_apply_top_k")
          onApply(topK)
        },
        modifier = Modifier.align(Alignment.End).padding(bottom = 24.dp),
        contentPadding = SMALL_BUTTON_CONTENT_PADDING,
      ) {
        Text(stringResource(R.string.videomomentfinder_config_apply))
      }
    }
  }
}
