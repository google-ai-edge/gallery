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
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import android.util.Size
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraInfoUnavailableException
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.ai.edge.gallery.R
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

private const val TAG = "AGLiveCameraView"

/**
 * Displays a live camera feed and delivers frames to [onBitmap], with optional support for taking
 * high-resolution still photos on demand.
 *
 * @param onBitmap Called for each frame from the continuous camera stream. The caller must call
 *   `imageProxy.close()` when done processing the frame.
 * @param modifier [Modifier] applied to the preview container when [renderPreview] is `true`.
 * @param preferredSize Target resolution (in pixels) for the continuous camera stream delivered to
 *   [onBitmap] (e.g., `1080` for 1080p).
 * @param photoCapturePreferredSize Optional target resolution (in pixels) for still photo captures
 *   (e.g., `3840` for 4K). Useful for capturing high-res photos while keeping the continuous camera
 *   stream ([onBitmap]) at a lower resolution. When `null`, still photo capture is disabled.
 * @param onPhotoCaptureProviderChanged Provides a suspend function that can be called to capture a
 *   single high-res photo at [photoCapturePreferredSize], or `null` when photo capture is disabled
 *   or the camera is not active.
 * @param outputImageFormat Pixel format of the raw [ImageProxy] passed to [onBitmap] (the [Bitmap]
 *   passed to [onBitmap] and returned by the photo capture function is always `ARGB_8888`).
 * @param renderPreview Whether to draw the camera preview on screen. Set to `false` if you render
 *   the preview yourself (for example, on a custom OpenGL surface).
 * @param cameraSelector Which camera to use (e.g., front or back camera).
 * @param isPaused When `true`, freezes the on-screen preview (and skips frame decoding if
 *   [skipFramesWhilePaused] is also `true`).
 * @param onError Optional callback invoked if the camera fails to start.
 * @param fallBackToAvailableCamera When `true`, falls back to another available camera if
 *   [cameraSelector] is not available on the device.
 * @param skipFramesWhilePaused When `true` and [isPaused] is `true`, drops incoming camera frames
 *   immediately without decoding them into bitmaps.
 */
@Composable
fun LiveCameraView(
  onBitmap: (Bitmap, ImageProxy) -> Unit,
  modifier: Modifier = Modifier,
  preferredSize: Int = 500,
  photoCapturePreferredSize: Int? = null,
  onPhotoCaptureProviderChanged: ((suspend () -> Bitmap?)?) -> Unit = {},
  @ImageAnalysis.OutputImageFormat
  outputImageFormat: Int = ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888,
  renderPreview: Boolean = true,
  cameraSelector: CameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA,
  isPaused: Boolean = false,
  onError: (() -> Unit)? = null,
  fallBackToAvailableCamera: Boolean = false,
  skipFramesWhilePaused: Boolean = false,
  onPermissionDeniedChanged: (Boolean) -> Unit = {},
  permissionDeniedContent: (@Composable () -> Unit)? = null,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val lifecycleOwner = LocalLifecycleOwner.current
  val flowTrackerVersion by PermissionAccessTracker.observeStateVersion(context).collectAsState()
  val snapshotTrackerVersion = PermissionAccessTracker.currentStateVersion(context)
  var imageBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
  var cameraProvider: ProcessCameraProvider? by remember { mutableStateOf(null) }
  var isPermissionDenied by remember {
    mutableStateOf(PermissionAccessTracker.isPermissionDenied(context, PermissionAccessKind.CAMERA))
  }
  val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
  // The photo capture function of the currently bound camera, or null if there is none.
  var photoCaptureProvider by remember { mutableStateOf<(suspend () -> Bitmap?)?>(null) }

  val currentOnBitmap by rememberUpdatedState(onBitmap)
  val currentOnPhotoCaptureProviderChanged by rememberUpdatedState(onPhotoCaptureProviderChanged)
  val currentIsPaused by rememberUpdatedState(isPaused)
  val currentSkipFramesWhilePaused by rememberUpdatedState(skipFramesWhilePaused)
  val currentOnError by rememberUpdatedState(onError)
  val currentOnPermissionDeniedChanged by rememberUpdatedState(onPermissionDeniedChanged)

  LaunchedEffect(isPermissionDenied) { currentOnPermissionDeniedChanged(isPermissionDenied) }

  // Reports the provider whenever it changes, and also to a new callback instance passed in by a
  // recomposition, which would otherwise never learn about an already bound camera.
  LaunchedEffect(photoCaptureProvider, onPhotoCaptureProviderChanged) {
    onPhotoCaptureProviderChanged(photoCaptureProvider)
  }

  val onBitmapFn: (Bitmap, ImageProxy) -> Unit = { bitmap, imageProxy ->
    if (!currentIsPaused) {
      imageBitmap = bitmap.asImageBitmap()
    }
    currentOnBitmap(bitmap, imageProxy)
  }

  suspend fun bindCamera() {
    cameraProvider =
      startCamera(
        context = context,
        lifecycleOwner = lifecycleOwner,
        cameraExecutor = cameraExecutor,
        onBitmap = onBitmapFn,
        preferredSize = preferredSize,
        photoCapturePreferredSize = photoCapturePreferredSize,
        onPhotoCaptureProviderChanged = { photoCaptureProvider = it },
        outputImageFormat = outputImageFormat,
        cameraSelector = cameraSelector,
        fallBackToAvailableCamera = fallBackToAvailableCamera,
        shouldSkipFrame = { currentSkipFramesWhilePaused && currentIsPaused },
        onError = currentOnError,
      )
  }

  fun unbindCameraForDeniedPermission() {
    photoCaptureProvider = null
    cameraProvider?.unbindAll()
    cameraProvider = null
    imageBitmap = null
    isPermissionDenied = true
  }

  fun syncCameraPermissionState() {
    when {
      PermissionAccessTracker.isPermissionGranted(context, PermissionAccessKind.CAMERA) -> {
        if (isPermissionDenied) {
          isPermissionDenied = false
          scope.launch { bindCamera() }
        }
      }
      PermissionAccessTracker.isPermissionDenied(context, PermissionAccessKind.CAMERA) -> {
        unbindCameraForDeniedPermission()
      }
    }
  }

  val liveCameraPermissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
      permissionGranted ->
      PermissionAccessTracker.onPermissionResult(
        context,
        Manifest.permission.CAMERA,
        permissionGranted,
      )
      val effectiveGranted =
        permissionGranted &&
          PermissionAccessTracker.isPermissionGranted(context, PermissionAccessKind.CAMERA)
      isPermissionDenied = !effectiveGranted
      if (effectiveGranted) {
        scope.launch { bindCamera() }
      }
    }

  LaunchedEffect(
    cameraSelector,
    preferredSize,
    photoCapturePreferredSize,
    outputImageFormat,
    fallBackToAvailableCamera,
  ) {
    when {
      PermissionAccessTracker.isPermissionGranted(context, PermissionAccessKind.CAMERA) -> {
        isPermissionDenied = false
        bindCamera()
      }
      PermissionAccessTracker.isPermissionDenied(context, PermissionAccessKind.CAMERA) -> {
        unbindCameraForDeniedPermission()
      }
      else -> {
        liveCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
      }
    }
  }

  LaunchedEffect(flowTrackerVersion, snapshotTrackerVersion) { syncCameraPermissionState() }

  LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { syncCameraPermissionState() }

  DisposableEffect(Unit) {
    onDispose {
      // Effects no longer run after disposal, so the callback is told directly.
      currentOnPhotoCaptureProviderChanged(null)
      cameraProvider?.unbindAll()
      cameraExecutor.shutdown()
    }
  }

  // Camera live view.
  if (renderPreview) {
    Box(modifier = modifier.background(Color.Black)) {
      val ib = imageBitmap
      if (ib != null) {
        Canvas(modifier = Modifier.fillMaxSize()) {
          val bitmapWidth = ib.width.toFloat()
          val bitmapHeight = ib.height.toFloat()
          val canvasWidth = size.width
          val canvasHeight = size.height

          // Calculate the scale to fill the canvas while maintaining aspect ratio
          val scale: Float =
            if (bitmapWidth / bitmapHeight > canvasWidth / canvasHeight) {
              canvasHeight / bitmapHeight
            } else {
              canvasWidth / bitmapWidth
            }

          // Calculate the source rectangle (what to draw from the bitmap)
          val srcLeft = (bitmapWidth - canvasWidth / scale) / 2
          val srcTop = (bitmapHeight - canvasHeight / scale) / 2
          val srcRight = srcLeft + canvasWidth / scale
          val srcBottom = srcTop + canvasHeight / scale
          val srcRect = Rect(srcLeft, srcTop, srcRight, srcBottom)

          // The destination rectangle is the entire canvas
          val dstRect = Rect(0f, 0f, canvasWidth, canvasHeight)

          // Draw the bitmap with the calculated source and destination rectangles
          drawImage(
            image = ib,
            srcOffset = IntOffset(srcRect.topLeft.x.toInt(), srcRect.topLeft.y.toInt()),
            srcSize = IntSize(srcRect.width.toInt(), srcRect.height.toInt()),
            dstOffset = IntOffset(dstRect.topLeft.x.toInt(), dstRect.topLeft.y.toInt()),
            dstSize = IntSize(dstRect.width.toInt(), dstRect.height.toInt()),
          )
        }
      }
      if (isPermissionDenied) {
        permissionDeniedContent?.invoke()
      }
    }
  }
}

/**
 * Asynchronously initializes and binds the camera's [ImageAnalysis] stream and optional
 * high-resolution [ImageCapture] use case to [lifecycleOwner].
 */
private suspend fun startCamera(
  context: Context,
  lifecycleOwner: LifecycleOwner,
  cameraExecutor: Executor,
  onBitmap: (Bitmap, ImageProxy) -> Unit,
  preferredSize: Int,
  photoCapturePreferredSize: Int?,
  onPhotoCaptureProviderChanged: ((suspend () -> Bitmap?)?) -> Unit,
  @ImageAnalysis.OutputImageFormat outputImageFormat: Int,
  cameraSelector: CameraSelector,
  fallBackToAvailableCamera: Boolean,
  shouldSkipFrame: () -> Boolean,
  onError: (() -> Unit)? = null,
): ProcessCameraProvider {
  val cameraProvider = ProcessCameraProvider.awaitInstance(context)

  val effectiveCameraSelector =
    if (fallBackToAvailableCamera) {
      resolveAvailableCameraSelector(cameraProvider, cameraSelector)
    } else {
      cameraSelector
    }

  val imageAnalysis =
    ImageAnalysis.Builder()
      .setResolutionSelector(buildResolutionSelector(preferredSize))
      .setOutputImageFormat(outputImageFormat)
      .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
      .build()
      .also {
        it.setAnalyzer(cameraExecutor) { imageProxy ->
          // When paused (e.g. during LLM inference), avoid any bitmap decoding or CPU overhead.
          if (shouldSkipFrame()) {
            imageProxy.close()
            return@setAnalyzer
          }

          val bitmap = imageProxyToUprightBitmapOrNull(imageProxy, effectiveCameraSelector)
          if (bitmap == null) {
            // An unclosed ImageProxy would stall the camera stream.
            imageProxy.close()
            return@setAnalyzer
          }
          // The caller is responsible for calling `.close()` on imageProxy when done processing.
          onBitmap(bitmap, imageProxy)
        }
      }

  val imageCapture = photoCapturePreferredSize?.let { size ->
    ImageCapture.Builder()
      .setResolutionSelector(buildResolutionSelector(size))
      .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
      .build()
  }

  try {
    cameraProvider.unbindAll()
    val useCases = listOfNotNull(imageAnalysis, imageCapture).toTypedArray()
    cameraProvider.bindToLifecycle(lifecycleOwner, effectiveCameraSelector, *useCases)
    onPhotoCaptureProviderChanged(
      imageCapture?.let { capture ->
        { capturePhoto(capture, cameraExecutor, effectiveCameraSelector) }
      }
    )
  } catch (exc: Exception) {
    // unbindAll() above may already have unbound a previously provided photo capture.
    onPhotoCaptureProviderChanged(null)
    Log.e(TAG, "Failed to start camera", exc)
    Toast.makeText(context, R.string.camera_init_failed, Toast.LENGTH_LONG).show()
    onError?.invoke()
  }
  return cameraProvider
}

/**
 * Builds a [ResolutionSelector] targeting a square bounding box of `preferredSize x preferredSize`,
 * preferring the closest supported camera resolution at or below that bound before falling back to
 * higher resolutions.
 *
 * The aspect ratio is pinned to 4:3 (where the camera supports it) so that [ImageAnalysis] and
 * [ImageCapture] cover the same field of view even though they use different resolutions. Without
 * it, each use case could settle on a different aspect ratio for its size bound.
 */
private fun buildResolutionSelector(preferredSize: Int): ResolutionSelector =
  ResolutionSelector.Builder()
    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
    .setResolutionStrategy(
      ResolutionStrategy(
        Size(preferredSize, preferredSize),
        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
      )
    )
    .build()

/**
 * Takes a single in-memory picture using [imageCapture] and decodes it into an upright [Bitmap],
 * returning `null` if capture or decoding fails.
 */
private suspend fun capturePhoto(
  imageCapture: ImageCapture,
  cameraExecutor: Executor,
  effectiveCameraSelector: CameraSelector,
): Bitmap? {
  val image = awaitCapturedImage(imageCapture, cameraExecutor) ?: return null
  return try {
    // Decoding a high-res photo is slow, so it must not run on cameraExecutor, where it would
    // hold up the frames of the continuous camera stream.
    withContext(Dispatchers.Default) {
      ensureActive()
      val bitmap = imageProxyToUprightBitmapOrNull(image, effectiveCameraSelector)
      // Nobody receives the bitmap if the caller was cancelled in the meantime.
      if (!isActive) bitmap?.recycle()
      bitmap
    }
  } finally {
    image.close()
  }
}

/**
 * Takes a single in-memory picture using [imageCapture], returning `null` if the capture fails. The
 * caller must close the returned [ImageProxy].
 */
private suspend fun awaitCapturedImage(
  imageCapture: ImageCapture,
  cameraExecutor: Executor,
): ImageProxy? = suspendCancellableCoroutine { continuation ->
  fun onCaptureNotStarted(e: RuntimeException) {
    Log.w(TAG, "High-res image capture could not be started", e)
    continuation.resume(null) { _, _, _ -> }
  }
  try {
    imageCapture.takePicture(
      cameraExecutor,
      object : ImageCapture.OnImageCapturedCallback() {
        override fun onCaptureSuccess(image: ImageProxy) {
          // Closes the image if the caller was cancelled and so never receives it.
          continuation.resume(image) { _, undelivered, _ -> undelivered?.close() }
        }

        override fun onError(exception: ImageCaptureException) {
          Log.w(TAG, "High-res image capture failed", exception)
          continuation.resume(null) { _, _, _ -> }
        }
      },
    )
  } catch (e: IllegalStateException) {
    // The use case is no longer bound to a camera.
    onCaptureNotStarted(e)
  } catch (e: RejectedExecutionException) {
    // cameraExecutor has already been shut down.
    onCaptureNotStarted(e)
  }
}

/**
 * Converts [imageProxy] to an upright [Bitmap] like [imageProxyToUprightBitmap], returning `null`
 * if the image cannot be converted.
 */
private fun imageProxyToUprightBitmapOrNull(
  imageProxy: ImageProxy,
  effectiveCameraSelector: CameraSelector,
): Bitmap? =
  try {
    imageProxyToUprightBitmap(imageProxy, effectiveCameraSelector)
  } catch (e: IllegalArgumentException) {
    // ImageProxy.toBitmap() does not support the image format.
    Log.e(TAG, "Failed to convert camera image to a bitmap", e)
    null
  } catch (e: UnsupportedOperationException) {
    // ImageProxy.toBitmap() failed to convert the image.
    Log.e(TAG, "Failed to convert camera image to a bitmap", e)
    null
  }

/**
 * Converts [imageProxy] to a [Bitmap], applying [ImageProxy.getImageInfo] rotation degrees so the
 * returned image is upright and mirroring horizontally when using the front camera.
 */
private fun imageProxyToUprightBitmap(
  imageProxy: ImageProxy,
  effectiveCameraSelector: CameraSelector,
): Bitmap {
  var bitmap = imageProxy.toBitmap()
  val rotation = imageProxy.imageInfo.rotationDegrees
  val matrix = Matrix()
  if (rotation != 0) {
    matrix.postRotate(rotation.toFloat())
  }
  if (effectiveCameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA) {
    matrix.postScale(-1f, 1f)
  }
  if (!matrix.isIdentity) {
    val transformed = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    if (transformed != bitmap) {
      bitmap.recycle()
      bitmap = transformed
    }
  }
  return bitmap
}

/**
 * Returns [cameraSelector] if the device has that camera, otherwise the front or back camera,
 * whichever is available. Falls back to [cameraSelector] if neither is available.
 */
private fun resolveAvailableCameraSelector(
  cameraProvider: ProcessCameraProvider,
  cameraSelector: CameraSelector,
): CameraSelector {
  fun hasCamera(selector: CameraSelector): Boolean =
    try {
      cameraProvider.hasCamera(selector)
    } catch (e: CameraInfoUnavailableException) {
      false
    }
  return when {
    hasCamera(cameraSelector) -> cameraSelector
    cameraSelector != CameraSelector.DEFAULT_FRONT_CAMERA &&
      hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
    cameraSelector != CameraSelector.DEFAULT_BACK_CAMERA &&
      hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
    else -> cameraSelector
  }
}
