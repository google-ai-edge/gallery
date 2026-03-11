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
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

private const val TAG = "AGMessageBodyWebview"
private val iframeWrapper =
  """
  <html>
    <body style="margin:0;padding:0;">
      <iframe
          width="100%"
          height="100%"
          src="___"
          frameborder="0"
          style="border:0;">
      </iframe>
    </body>
  </html>
  """
    .trimIndent()

/** A Composable that displays a WebView to render web content within a chat message. */
@Composable
fun MessageBodyWebview(message: ChatMessageWebView, modifier: Modifier = Modifier) {
  AndroidView(
    modifier = modifier.fillMaxWidth().aspectRatio(4f / 3f),
    factory = { context ->
      WebView(context).apply {
        layoutParams =
          ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
          )
        settings.apply {
          javaScriptEnabled = true
          domStorageEnabled = true
          allowFileAccess = true
        }

        // Prevent scrolling parent composable (e.g. a LazyColumn).
        setOnTouchListener { v, event ->
          v.parent.requestDisallowInterceptTouchEvent(true)
          false
        }

        webChromeClient =
          object : WebChromeClient() {
            // Log console messages.
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
              Log.d(
                TAG,
                "${consoleMessage?.message()} -- From line ${consoleMessage?.lineNumber()} of ${consoleMessage?.sourceId()}",
              )
              return super.onConsoleMessage(consoleMessage)
            }
          }

        if (message.url.isNotEmpty()) {
          if (message.iframe) {
            loadDataWithBaseURL(
              null,
              iframeWrapper.replace("___", message.url),
              "text/html",
              "UTF-8",
              null,
            )
          } else {
            loadUrl(message.url)
          }
        }
      }
    },
  )
}
