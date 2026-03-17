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

package com.google.ai.edge.gallery.ui.common.chat

import android.util.Log
import android.webkit.ConsoleMessage
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.google.ai.edge.gallery.ui.common.GalleryWebView

private const val TAG = "AGMessageBodyWebview"

/** A Composable that displays a WebView to render web content within a chat message. */
@Composable
fun MessageBodyWebview(message: ChatMessageWebView, modifier: Modifier = Modifier) {
  GalleryWebView(
    modifier = modifier.fillMaxWidth().aspectRatio(4f / 3f),
    initialUrl = message.url,
    useIframeWrapper = message.iframe,
    preventParentScrolling = true,
    allowRequestPermission = true,
    onConsoleMessage = { consoleMessage: ConsoleMessage? ->
      Log.d(
        TAG,
        "${consoleMessage?.message()} -- From line ${consoleMessage?.lineNumber()} of ${consoleMessage?.sourceId()}",
      )
      true // Return true to indicate the message was handled.
    },
  )
}
