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

package com.google.ai.edge.gallery.ui.common

import android.Manifest
import android.content.Context
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import com.google.ai.edge.gallery.common.LOCAL_URL_BASE
import java.io.File

private const val TAG = "AGGalleryWebView"
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

/**
 * A base [WebViewClient] for [GalleryWebView] that handles local asset loading and logs page
 * finishing.
 */
open class BaseGalleryWebViewClient(private val context: Context) : WebViewClient() {
  private val localFileAssetsLoader =
    WebViewAssetLoader.Builder()
      .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
      .addPathHandler("/", WebViewAssetLoader.InternalStoragePathHandler(context, context.filesDir))
      .build()

  override fun shouldInterceptRequest(
    view: WebView?,
    request: WebResourceRequest?,
  ): WebResourceResponse? {
    if (request?.url != null && request.url.toString().startsWith(LOCAL_URL_BASE)) {
      // Returns 404 if file not exist for imported skills.
      if (!request.url.toString().startsWith("$LOCAL_URL_BASE/assets/")) {
        val path = request.url.path ?: ""
        val localFile = File(context.filesDir, path)
        if (!localFile.exists() || localFile.isDirectory) {
          return WebResourceResponse("text/plain", "UTF-8", null)
        }
      }
      return localFileAssetsLoader.shouldInterceptRequest(request.url)
    }
    return super.shouldInterceptRequest(view, request)
  }
}

/**
 * A reusable Composable that wraps an Android WebView, providing common configurations and handling
 * for permissions, local asset loading, and JavaScript interfaces.
 */
@Composable
fun GalleryWebView(
  modifier: Modifier = Modifier,
  initialUrl: String? = null,
  useIframeWrapper: Boolean = false,
  preventParentScrolling: Boolean = false,
  allowRequestPermission: Boolean = false,
  onWebViewCreated: ((WebView) -> Unit)? = null,
  onConsoleMessage: ((ConsoleMessage?) -> Unit)? = null,
  onPermissionRequest: ((PermissionRequest?) -> Unit)? = null,
  customWebViewClient: WebViewClient? = null,
) {
  val context = LocalContext.current

  val curWebViewClient = remember {
    customWebViewClient ?: BaseGalleryWebViewClient(context = context)
  }
  var pendingCameraPermissionRequest by remember { mutableStateOf<PermissionRequest?>(null) }
  var pendingAudioPermissionRequest by remember { mutableStateOf<PermissionRequest?>(null) }

  val cameraPermissionLauncher =
    rememberLauncherForActivityResult(contract = ActivityResultContracts.RequestPermission()) {
      isGranted: Boolean ->
      pendingCameraPermissionRequest?.let { request ->
        if (isGranted) {
          request.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
        } else {
          // If camera is denied, we don't call request.deny() on the whole request,
          // as it might contain other resources. The WebView will handle the denial
          // of the specific camera resource.
        }
        pendingCameraPermissionRequest = null
      }
    }

  val audioPermissionLauncher =
    rememberLauncherForActivityResult(contract = ActivityResultContracts.RequestPermission()) {
      isGranted: Boolean ->
      pendingAudioPermissionRequest?.let { request ->
        if (isGranted) {
          request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
        } else {
          // Similar to camera, don't call request.deny() on the whole request.
        }
        pendingAudioPermissionRequest = null
      }
    }

  AndroidView(
    modifier = modifier,
    factory = { ctx ->
      WebView(ctx).apply {
        layoutParams =
          ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
          )

        settings.apply {
          javaScriptEnabled = true
          domStorageEnabled = true
          allowFileAccess = true
          mediaPlaybackRequiresUserGesture = false
        }

        if (preventParentScrolling) {
          setOnTouchListener { v, event ->
            v.parent.requestDisallowInterceptTouchEvent(true)
            false
          }
        }

        webChromeClient =
          object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
              Log.d(
                TAG,
                "${consoleMessage?.message()} -- From line ${consoleMessage?.lineNumber()} of ${consoleMessage?.sourceId()}",
              )
              onConsoleMessage?.invoke(consoleMessage)
              return super.onConsoleMessage(consoleMessage)
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
              if (!allowRequestPermission) {
                request?.deny()
                return
              }

              if (request == null) return
              onPermissionRequest?.invoke(request)
                ?: run {
                  val resources = request.resources
                  val isCameraRequest = resources.any {
                    it == PermissionRequest.RESOURCE_VIDEO_CAPTURE
                  }
                  val isAudioRequest = resources.any {
                    it == PermissionRequest.RESOURCE_AUDIO_CAPTURE
                  }

                  if (isCameraRequest) {
                    pendingCameraPermissionRequest = request
                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                  }

                  if (isAudioRequest) {
                    pendingAudioPermissionRequest = request
                    audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                  }

                  val otherResources =
                    resources
                      .filter {
                        it != PermissionRequest.RESOURCE_VIDEO_CAPTURE &&
                          it != PermissionRequest.RESOURCE_AUDIO_CAPTURE
                      }
                      .toTypedArray()
                  if (otherResources.isNotEmpty()) {
                    request.grant(otherResources)
                  }
                }
            }
          }

        webViewClient = curWebViewClient

        initialUrl?.let { url ->
          if (useIframeWrapper) {
            loadDataWithBaseURL(null, iframeWrapper.replace("___", url), "text/html", "UTF-8", null)
          } else {
            loadUrl(url)
          }
        }
        onWebViewCreated?.invoke(this)
      }
    },
    onRelease = { webView ->
      webView.stopLoading()
      webView.destroy()
    },
  )
}
