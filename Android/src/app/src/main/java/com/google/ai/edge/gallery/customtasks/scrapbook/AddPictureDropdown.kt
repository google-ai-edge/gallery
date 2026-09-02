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
package com.google.ai.edge.gallery.customtasks.scrapbook

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.common.decodeSampledBitmapFromUri
import com.google.ai.edge.gallery.common.rotateBitmap
import java.io.File

@Composable
fun AddPictureDropdown(
  show: Boolean,
  onSelectBitmap: (Bitmap) -> Unit,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
  extraContent: @Composable () -> Unit = {},
) {
  var tempPhotoUri by remember { mutableStateOf<Uri?>(null) }
  val context = LocalContext.current

  // Registers a photo picker activity launcher in single-select mode.
  val pickPicture =
    rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
      if (uri != null) {
        decodeBitmap(context = context, uri = uri)?.let { onSelectBitmap(it) }
      }
    }

  // Launch camera.
  val cameraLauncher =
    rememberLauncherForActivityResult(contract = ActivityResultContracts.TakePicture()) { success ->
      if (success) {
        val uri = tempPhotoUri
        if (uri != null) {
          decodeBitmap(context = context, uri = uri)?.let { onSelectBitmap(it) }
        }
      }
    }

  // Permission request when taking picture.
  val takePicturePermissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
      permissionGranted ->
      if (permissionGranted) {
        val uri = createTempImageUri(context = context)
        tempPhotoUri = uri
        cameraLauncher.launch(uri)
      }
    }

  DropdownMenu(expanded = show, onDismissRequest = onDismiss, modifier = modifier) {
    // Pick an image from album.
    DropdownMenuItem(
      text = {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Icon(
            Icons.Rounded.Photo,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
          )
          Text(stringResource(R.string.pick_from_album))
        }
      },
      onClick = {
        // Launch the photo picker and let the user choose only images.
        pickPicture.launch(
          PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
        onDismiss()
      },
    )
    // Take a picture.
    DropdownMenuItem(
      text = {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Icon(
            Icons.Rounded.PhotoCamera,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
          )
          Text(stringResource(R.string.take_a_picture))
        }
      },
      onClick = {
        takePicturePermissionLauncher.launch(Manifest.permission.CAMERA)
        onDismiss()
      },
    )
    extraContent()
  }
}

private fun createTempImageUri(context: Context): Uri {
  // Ensure the images cache directory exists.
  val cachePath = File(context.cacheDir, "images")
  cachePath.mkdirs()
  val file = File(context.cacheDir, "images${File.separator}temp_image.jpg")
  return FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
}

private fun decodeBitmap(context: Context, uri: Uri): Bitmap? {
  return try {
    val inputStream = context.contentResolver.openInputStream(uri)
    if (inputStream != null) {
      // Read the EXIF metadata from the picture and rotate it correctly.
      val exif = ExifInterface(inputStream)
      val orientation =
        exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
      // You MUST close the first input stream before opening another one on the same URI.
      inputStream.close()

      // The let block will now return the rotated bitmap
      decodeSampledBitmapFromUri(context, uri, 1024, 1024)?.let { originalBitmap ->
        rotateBitmap(bitmap = originalBitmap, orientation = orientation)
      }
    } else {
      null
    }
  } catch (e: Exception) {
    e.printStackTrace()
    null
  }
}
