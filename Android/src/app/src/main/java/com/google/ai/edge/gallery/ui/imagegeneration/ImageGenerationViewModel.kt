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

package com.google.ai.edge.gallery.ui.imagegeneration

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.TASK_IMAGE_GENERATION
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageImageWithHistory
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageLoading
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageType
import com.google.ai.edge.gallery.ui.common.chat.ChatSide
import com.google.ai.edge.gallery.ui.common.chat.ChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ImageGenerationViewModel : ChatViewModel(task = TASK_IMAGE_GENERATION) {
  fun generateResponse(model: Model, input: String) {
    viewModelScope.launch(Dispatchers.Default) {
      setInProgress(true)

      // Loading.
      addMessage(
        model = model,
        message = ChatMessageLoading(),
      )

      // Wait for model to be initialized.
      while (model.instance == null) {
        delay(100)
      }

      // Run inference.
      val bitmaps: MutableList<Bitmap> = mutableListOf()
      val imageBitmaps: MutableList<ImageBitmap> = mutableListOf()
      ImageGenerationModelHelper.runInference(
        model = model, input = input
      ) { step, totalIterations, result, isLast ->
        bitmaps.add(result.bitmap)
        imageBitmaps.add(result.bitmap.asImageBitmap())
        val message = ChatMessageImageWithHistory(
          bitmaps = bitmaps,
          imageBitMaps = imageBitmaps,
          totalIterations = totalIterations,
          side = ChatSide.AGENT,
          latencyMs = result.latencyMs,
          curIteration = step,
        )
        if (step == 0) {
          removeLastMessage(model = model)

          super.addMessage(
            model = model,
            message = message,
          )
        } else {
          super.replaceLastMessage(
            model = model,
            message = message,
            type = ChatMessageType.IMAGE_WITH_HISTORY
          )
        }

        if (isLast) {
          setInProgress(false)
        }
      }
    }
  }
}
