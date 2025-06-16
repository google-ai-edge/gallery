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

package com.google.ai.edge.gallery.ui.preview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.common.Classification
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageClassification
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageImage
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageText
import com.google.ai.edge.gallery.ui.common.chat.ChatSide
import com.google.ai.edge.gallery.ui.common.chat.ChatViewModel

class PreviewChatModel(context: Context) : ChatViewModel(task = TASK_TEST1) {
  init {
    val model = task.models[1]
    addMessage(
      model = model,
      message =
        ChatMessageText(
          content =
            "Thanks everyone for your enthusiasm on the team lunch, but people who can sign on the cheque is OOO next week \uD83D\uDE02,",
          side = ChatSide.USER,
        ),
    )
    addMessage(
      model = model,
      message =
        ChatMessageText(content = "Today is Wednesday!", side = ChatSide.AGENT, latencyMs = 1232f),
    )
    addMessage(
      model = model,
      message =
        ChatMessageClassification(
          classifications =
            listOf(
              Classification(label = "label1", score = 0.3f, color = Color.Red),
              Classification(label = "label2", score = 0.7f, color = Color.Blue),
            ),
          latencyMs = 12345f,
        ),
    )
    val bitmap =
      getBitmapFromVectorDrawable(
        context = context,
        drawableId = R.drawable.ic_launcher_background,
      )!!
    addMessage(
      model = model,
      message =
        ChatMessageImage(
          bitmap = bitmap,
          imageBitMap = bitmap.asImageBitmap(),
          side = ChatSide.USER,
        ),
    )
  }

  private fun getBitmapFromVectorDrawable(context: Context, drawableId: Int): Bitmap? {
    val drawable: Drawable =
      ContextCompat.getDrawable(context, drawableId) ?: return null // Drawable not found

    val bitmap = createBitmap(drawable.intrinsicWidth, drawable.intrinsicHeight)
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, canvas.width, canvas.height)
    drawable.draw(canvas)

    return bitmap
  }
}
