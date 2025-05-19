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

package com.google.ai.edge.gallery.ui.common.chat

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Composable function to display a live camera feed in a dialog.
 *
 * This function renders a dialog that displays a live camera preview, along with optional
 * classification results and FPS information. It manages camera initialization, frame capture,
 * and dialog dismissal.
 */
@Composable
fun LiveCameraDialog(
  onDismissed: (averageFps: Int) -> Unit,
  onBitmap: (Bitmap) -> Unit,
  streamingMessage: ChatMessage? = null,
) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  var imageBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
  var cameraProvider: ProcessCameraProvider? by remember { mutableStateOf(null) }
  var sumFps by remember { mutableLongStateOf(0L) }
  var fpsCount by remember { mutableLongStateOf(0L) }

  LaunchedEffect(key1 = true) {
    cameraProvider = startCamera(
      context,
      lifecycleOwner,
      onBitmap = onBitmap,
      onImageBitmap = { b -> imageBitmap = b })
  }

  Dialog(onDismissRequest = {
    cameraProvider?.unbindAll()
    onDismissed((sumFps / fpsCount).toInt())
  }) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
      Column(
        modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)
      ) {
        // Title
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
          modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
        ) {
          Text(
            "Live camera",
            style = MaterialTheme.typography.titleLarge,
          )
          if (streamingMessage != null) {
            val fps = (1000f / streamingMessage.latencyMs).toInt()
            sumFps += fps.toLong()
            fpsCount += 1

            Text(
              "%d FPS".format(fps),
              style = MaterialTheme.typography.titleLarge,
            )
          }
        }

        // Camera live view.
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f),
          horizontalArrangement = Arrangement.Center
        ) {
          val ib = imageBitmap
          if (ib != null) {
            Image(
              bitmap = ib,
              contentDescription = "",
              modifier = Modifier
                .fillMaxHeight()
                .clip(RoundedCornerShape(8.dp)),
              contentScale = ContentScale.Inside
            )
          }
        }

        // Result.
        if (streamingMessage != null && streamingMessage is ChatMessageClassification) {
          MessageBodyClassification(
            message = streamingMessage,
            modifier = Modifier.fillMaxWidth(),
            oneLineLabel = true
          )
        }

        // Button.
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
          horizontalArrangement = Arrangement.End,
        ) {
          TextButton(
            onClick = {
              cameraProvider?.unbindAll()
              onDismissed((sumFps / fpsCount).toInt())
            },
          ) {
            Text("OK")
          }
        }
      }
    }
  }
}

/**
 * Asynchronously initializes and starts the camera for image capture and analysis.
 *
 * This function sets up the camera using CameraX, configures image analysis, and binds
 * the camera lifecycle to the provided LifecycleOwner. It captures frames from the camera,
 * converts them to Bitmaps and ImageBitmaps, and invokes the provided callbacks.
 */
private suspend fun startCamera(
  context: android.content.Context,
  lifecycleOwner: androidx.lifecycle.LifecycleOwner,
  onBitmap: (Bitmap) -> Unit,
  onImageBitmap: (ImageBitmap) -> Unit
): ProcessCameraProvider? = suspendCoroutine { continuation ->
  val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

  cameraProviderFuture.addListener({
    val cameraProvider = cameraProviderFuture.get()

    val resolutionSelector = ResolutionSelector.Builder().setResolutionStrategy(
      ResolutionStrategy(
        Size(1080, 1080),
        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
      )
    ).build()
    val imageAnalysis =
      ImageAnalysis.Builder().setResolutionSelector(resolutionSelector)
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build().also {
          it.setAnalyzer(Executors.newSingleThreadExecutor()) { imageProxy ->
            var bitmap = imageProxy.toBitmap()
            val rotation = imageProxy.imageInfo.rotationDegrees
            bitmap = if (rotation != 0) {
              val matrix = Matrix().apply {
                postRotate(rotation.toFloat())
              }
              Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            } else bitmap
            onBitmap(bitmap)
            onImageBitmap(bitmap.asImageBitmap())
            imageProxy.close()
          }
        }

    val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    try {
      cameraProvider?.unbindAll()
      cameraProvider?.bindToLifecycle(
        lifecycleOwner, cameraSelector, imageAnalysis
      )
      // Resume with the provider
      continuation.resume(cameraProvider)
    } catch (exc: Exception) {
      // todo: Handle exceptions (e.g., camera initialization failure)
    }
  }, ContextCompat.getMainExecutor(context))
}
