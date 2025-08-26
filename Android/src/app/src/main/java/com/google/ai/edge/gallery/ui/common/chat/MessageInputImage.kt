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

// import androidx.compose.ui.tooling.preview.Preview
// import com.google.ai.edge.gallery.ui.theme.GalleryTheme

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.ai.edge.gallery.ui.common.createTempPictureUri

private const val TAG = "AGMessageInputImage"

/**
 * Composable function to display image input options for chat messages.
 *
 * This function renders a row containing buttons that allow the user to select images from albums,
 * take photos using the camera, or initiate a live camera stream. It handles permission requests,
 * image selection, and launching camera activities.
 */
@Composable
fun MessageInputImage(
  onImageSelected: (Bitmap) -> Unit,
  streamingMessage: ChatMessage? = null,
  onStreamImage: (Bitmap) -> Unit = {},
  onStreamEnd: (Int) -> Unit = {},
  disableButtons: Boolean = false,
) {
  val context = LocalContext.current
  var tempPhotoUri by remember { mutableStateOf(value = Uri.EMPTY) }
  var showLiveCameraDialog by remember { mutableStateOf(false) }

  // Registers a photo picker activity launcher in single-select mode.
  val pickMedia =
    rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
      // Callback is invoked after the user selects a media item or closes the
      // photo picker.
      if (uri != null) {
        handleImageSelected(context = context, uri = uri, onImageSelected = onImageSelected)
      } else {
        Log.d(TAG, "No media selected")
      }
    }

  // launches camera
  val cameraLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { isImageSaved ->
      if (isImageSaved) {
        handleImageSelected(
          context = context,
          uri = tempPhotoUri,
          onImageSelected = onImageSelected,
          rotateForPortrait = true,
        )
      }
    }

  // Permission request when taking picture.
  val takePicturePermissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
      permissionGranted ->
      if (permissionGranted) {
        tempPhotoUri = context.createTempPictureUri()
        cameraLauncher.launch(tempPhotoUri)
      }
    }

  // Permission request when using live camera.
  val liveCameraPermissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
      permissionGranted ->
      if (permissionGranted) {
        showLiveCameraDialog = true
      }
    }

  val buttonAlpha = if (disableButtons) 0.3f else 1f

  Row(
    modifier = Modifier.fillMaxWidth().padding(12.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.End,
  ) {
    // Pick from albums.
    IconButton(
      onClick = {
        if (disableButtons) {
          return@IconButton
        }

        // Launch the photo picker and let the user choose only images.
        pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
      },
      colors =
        IconButtonDefaults.iconButtonColors(containerColor = MaterialTheme.colorScheme.primary),
      modifier = Modifier.alpha(buttonAlpha),
    ) {
      Icon(Icons.Rounded.Photo, contentDescription = "", tint = MaterialTheme.colorScheme.onPrimary)
    }

    // Take picture
    IconButton(
      onClick = {
        if (disableButtons) {
          return@IconButton
        }

        // Check permission
        when (PackageManager.PERMISSION_GRANTED) {
          // Already got permission. Call the lambda.
          ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) -> {
            tempPhotoUri = context.createTempPictureUri()
            cameraLauncher.launch(tempPhotoUri)
          }

          // Otherwise, ask for permission
          else -> {
            takePicturePermissionLauncher.launch(Manifest.permission.CAMERA)
          }
        }
      },
      colors =
        IconButtonDefaults.iconButtonColors(containerColor = MaterialTheme.colorScheme.primary),
      modifier = Modifier.alpha(buttonAlpha),
    ) {
      Icon(
        Icons.Rounded.PhotoCamera,
        contentDescription = "",
        tint = MaterialTheme.colorScheme.onPrimary,
      )
    }

    // Video stream.
    IconButton(
      onClick = {
        if (disableButtons) {
          return@IconButton
        }

        // Check permission
        when (PackageManager.PERMISSION_GRANTED) {
          // Already got permission. Call the lambda.
          ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) -> {
            showLiveCameraDialog = true
          }

          // Otherwise, ask for permission
          else -> {
            liveCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
          }
        }
      },
      colors =
        IconButtonDefaults.iconButtonColors(containerColor = MaterialTheme.colorScheme.primary),
      modifier = Modifier.alpha(buttonAlpha),
    ) {
      Icon(
        Icons.Rounded.Videocam,
        contentDescription = "",
        tint = MaterialTheme.colorScheme.onPrimary,
      )
    }

    // Video frame capture (1 FPS sampling)
    VideoFrameCaptureButton(
      onFramesCaptured = { frames ->
        frames.forEach { bitmap ->
          onImageSelected(bitmap)
        }
      },
      enabled = !disableButtons,
      modifier = Modifier.alpha(buttonAlpha)
    )
  }

  // Live camera stream dialog.
  if (showLiveCameraDialog) {
    // TODO(migration)
    //
    // LiveCameraDialog(
    //   streamingMessage = streamingMessage,
    //   onDismissed = { averageFps ->
    //     onStreamEnd(averageFps)
    //     showLiveCameraDialog = false
    //   },
    //   onBitmap = onStreamImage,
    // )
  }
}

private fun handleImageSelected(
  context: Context,
  uri: Uri,
  onImageSelected: (Bitmap) -> Unit,
  // For some reason, some Android phone would store the picture taken by the camera rotated
  // horizontally. Use this flag to rotate the image back to portrait if the picture's width
  // is bigger than height.
  rotateForPortrait: Boolean = false,
) {
  Log.d(TAG, "Selected URI: $uri")

  val bitmap: Bitmap? =
    try {
      val inputStream = context.contentResolver.openInputStream(uri)
      val tmpBitmap = BitmapFactory.decodeStream(inputStream)
      if (rotateForPortrait && tmpBitmap.width > tmpBitmap.height) {
        val matrix = Matrix()
        matrix.postRotate(90f)
        Bitmap.createBitmap(tmpBitmap, 0, 0, tmpBitmap.width, tmpBitmap.height, matrix, true)
      } else {
        tmpBitmap
      }
    } catch (e: Exception) {
      e.printStackTrace()
      null
    }
  if (bitmap != null) {
    onImageSelected(bitmap)
  }
}

// @Preview(showBackground = true)
// @Composable
// fun MessageInputImagePreview() {
//   GalleryTheme {
//     Column {
//       MessageInputImage(onImageSelected = {})
//       MessageInputImage(disableButtons = true, onImageSelected = {})
//     }
//   }
// }
