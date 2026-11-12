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
import android.net.Uri
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.webkit.WebViewAssetLoader
import com.google.ai.edge.gallery.common.LOCAL_URL_BASE
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException

private const val TAG = "AGGalleryWebView"
private const val ASSETS_PATH_PREFIX = "/assets/"
private const val SKILLS_PATH_PREFIX = "/skills/"
private const val SKILLS_DIR_NAME = "skills"
private const val DEFAULT_HTTPS_PORT = 443

private fun isAllowedWebScheme(uri: Uri?): Boolean {
  val scheme = uri?.scheme?.lowercase()
  return scheme == "https" || scheme == "http"
}

/** URL validation rules shared by the Gallery skill WebViews. */
object WebViewUrlPolicy {
  /**
   * Extracts a normalized `https://host` (or `https://host:port` for non-default ports) origin from
   * [url], or returns `null` if [url] is null, malformed, does not use the `https` scheme, or
   * contains a backslash or user-info component.
   *
   * Backslashes and user info are rejected because `android.net.Uri` and Chromium parse them
   * differently (Chromium treats `\` as `/` in `https:` URLs), so the host parsed here could
   * otherwise differ from the host the WebView actually connects to.
   */
  fun extractHttpsOrigin(url: String?): String? {
    if (url.isNullOrEmpty() || '\\' in url) return null
    val uri = url.toUri()
    val scheme = uri.scheme?.lowercase() ?: return null
    if (scheme != "https" || uri.userInfo != null) return null
    val host = uri.host?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
    return if (uri.port != -1 && uri.port != DEFAULT_HTTPS_PORT) {
      "$scheme://$host:${uri.port}"
    } else {
      "$scheme://$host"
    }
  }

  internal fun isTrustedLocalAssetOrigin(uri: Uri?): Boolean {
    return extractHttpsOrigin(uri?.toString()) == LOCAL_URL_BASE
  }

  /**
   * Returns `true` if [url] points to a canonical built-in APK asset served from [LOCAL_URL_BASE]
   * under `/assets/`, rejecting dot-segment (`..` / `.`) and backslash path-normalization tricks.
   */
  fun isTrustedBuiltInAssetUrl(url: String?): Boolean {
    if (url.isNullOrEmpty() || '\\' in url) return false
    val uri = url.toUri()
    if (!isTrustedLocalAssetOrigin(uri)) return false
    val decodedPath = uri.path ?: return false
    if (
      decodedPath.length <= ASSETS_PATH_PREFIX.length || !decodedPath.startsWith(ASSETS_PATH_PREFIX)
    ) {
      return false
    }
    if ('\\' in decodedPath) return false
    return decodedPath.split('/').none { it == "." || it == ".." }
  }
}

private fun escapeHtmlAttribute(value: String): String {
  return buildString(value.length) {
    for (ch in value) {
      when (ch) {
        '&' -> append("&amp;")
        '<' -> append("&lt;")
        '>' -> append("&gt;")
        '"' -> append("&quot;")
        '\'' -> append("&#39;")
        else -> append(ch)
      }
    }
  }
}

internal fun buildIframeWrapperHtml(url: String): String {
  val escapedUrl = escapeHtmlAttribute(url)
  return """
  <html>
    <body style="margin:0;padding:0;">
      <iframe
          width="100%"
          height="100%"
          src="$escapedUrl"
          frameborder="0"
          style="border:0;">
      </iframe>
    </body>
  </html>
  """
    .trimIndent()
}

private fun createNotFoundResponse(): WebResourceResponse {
  return WebResourceResponse(
    "text/plain",
    "UTF-8",
    404,
    "Not Found",
    emptyMap(),
    ByteArrayInputStream(ByteArray(0)),
  )
}

/**
 * A base [WebViewClient] for [GalleryWebView] that handles local asset loading and logs page
 * finishing.
 */
open class BaseGalleryWebViewClient(private val context: Context) : WebViewClient() {
  private val skillsRootDir = File(context.filesDir, SKILLS_DIR_NAME)

  private val localFileAssetsLoader =
    WebViewAssetLoader.Builder()
      .addPathHandler(ASSETS_PATH_PREFIX, WebViewAssetLoader.AssetsPathHandler(context))
      .addPathHandler(
        SKILLS_PATH_PREFIX,
        WebViewAssetLoader.InternalStoragePathHandler(context, skillsRootDir),
      )
      .build()

  override fun shouldInterceptRequest(
    view: WebView?,
    request: WebResourceRequest?,
  ): WebResourceResponse? {
    val url = request?.url ?: return super.shouldInterceptRequest(view, request)
    if (WebViewUrlPolicy.isTrustedLocalAssetOrigin(url)) {
      val rawPath = url.path ?: return createNotFoundResponse()
      return when {
        rawPath.startsWith(ASSETS_PATH_PREFIX) ->
          localFileAssetsLoader.shouldInterceptRequest(url) ?: createNotFoundResponse()
        rawPath.startsWith(SKILLS_PATH_PREFIX) -> {
          val relativePath = rawPath.removePrefix(SKILLS_PATH_PREFIX)
          if (relativePath.isEmpty() || !isValidSkillFile(relativePath)) {
            createNotFoundResponse()
          } else {
            localFileAssetsLoader.shouldInterceptRequest(url) ?: createNotFoundResponse()
          }
        }
        else -> {
          Log.w(TAG, "Blocked WebView access to non-allowlisted local path: $rawPath")
          createNotFoundResponse()
        }
      }
    }
    return super.shouldInterceptRequest(view, request)
  }

  override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
    val url = request?.url ?: return true
    if (!isAllowedWebScheme(url)) {
      Log.w(TAG, "Blocked WebView navigation to unsafe scheme: ${url.scheme}")
      return true
    }
    return false
  }

  @Deprecated("Deprecated in Java")
  override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
    if (url.isNullOrEmpty() || !isAllowedWebScheme(url.toUri())) {
      Log.w(TAG, "Blocked WebView navigation to unsafe URL.")
      return true
    }
    return false
  }

  private fun isValidSkillFile(relativePath: String): Boolean {
    return try {
      val rootCanonical = skillsRootDir.canonicalFile
      val targetCanonical = File(skillsRootDir, relativePath).canonicalFile
      targetCanonical.path.startsWith(rootCanonical.path + File.separator) && targetCanonical.isFile
    } catch (e: IOException) {
      Log.w(TAG, "Failed to resolve canonical skill path", e)
      false
    }
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
  val currentAllowRequestPermission by rememberUpdatedState(allowRequestPermission)
  val currentOnConsoleMessage by rememberUpdatedState(onConsoleMessage)
  val currentOnPermissionRequest by rememberUpdatedState(onPermissionRequest)

  val curWebViewClient = remember {
    customWebViewClient ?: BaseGalleryWebViewClient(context = context)
  }
  var pendingPermissionRequest by remember { mutableStateOf<PermissionRequest?>(null) }

  val mediaPermissionLauncher =
    rememberLauncherForActivityResult(
      contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grantResults: Map<String, Boolean> ->
      pendingPermissionRequest?.let { request ->
        val grantedWebResources = mutableListOf<String>()
        if (
          grantResults[Manifest.permission.CAMERA] == true &&
            PermissionRequest.RESOURCE_VIDEO_CAPTURE in request.resources
        ) {
          grantedWebResources.add(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
        }
        if (
          grantResults[Manifest.permission.RECORD_AUDIO] == true &&
            PermissionRequest.RESOURCE_AUDIO_CAPTURE in request.resources
        ) {
          grantedWebResources.add(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
        }
        if (grantedWebResources.isNotEmpty()) {
          request.grant(grantedWebResources.toTypedArray())
        } else {
          request.deny()
        }
        pendingPermissionRequest = null
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
          allowFileAccess = false
          allowContentAccess = false
          mediaPlaybackRequiresUserGesture = false
        }

        if (preventParentScrolling) {
          setOnTouchListener { v, _ ->
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
              currentOnConsoleMessage?.invoke(consoleMessage)
              return super.onConsoleMessage(consoleMessage)
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
              if (request == null) return
              if (
                !currentAllowRequestPermission ||
                  !WebViewUrlPolicy.isTrustedLocalAssetOrigin(request.origin) ||
                  !WebViewUrlPolicy.isTrustedBuiltInAssetUrl(this@apply.url)
              ) {
                Log.w(TAG, "Denied WebView permission request from origin: ${request.origin}")
                request.deny()
                return
              }

              currentOnPermissionRequest?.invoke(request)
                ?: run {
                  val permissionsToRequest = buildList {
                    if (PermissionRequest.RESOURCE_VIDEO_CAPTURE in request.resources) {
                      add(Manifest.permission.CAMERA)
                    }
                    if (PermissionRequest.RESOURCE_AUDIO_CAPTURE in request.resources) {
                      add(Manifest.permission.RECORD_AUDIO)
                    }
                  }

                  if (permissionsToRequest.isEmpty()) {
                    request.deny()
                    return
                  }

                  pendingPermissionRequest?.deny()
                  pendingPermissionRequest = request
                  mediaPermissionLauncher.launch(permissionsToRequest.toTypedArray())
                }
            }
          }

        webViewClient = curWebViewClient

        initialUrl?.let { url ->
          val parsedUri = url.toUri()
          if (isAllowedWebScheme(parsedUri)) {
            if (useIframeWrapper) {
              loadDataWithBaseURL(null, buildIframeWrapperHtml(url), "text/html", "UTF-8", null)
            } else {
              loadUrl(url)
            }
          } else {
            Log.w(TAG, "Blocked loading initialUrl with unsafe scheme: ${parsedUri.scheme}")
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
