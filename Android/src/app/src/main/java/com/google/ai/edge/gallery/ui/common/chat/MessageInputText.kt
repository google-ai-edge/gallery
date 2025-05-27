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

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FlipCameraAndroid
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PostAdd
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.ui.common.createTempPictureUri
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.gallery.ui.preview.PreviewModelManagerViewModel
import com.google.ai.edge.gallery.ui.theme.GalleryTheme
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

private const val TAG = "AGMessageInputText"

/**
 * Composable function to display a text input field for composing chat messages.
 *
 * This function renders a row containing a text field for message input and a send button.
 * It handles message composition, input validation, and sending messages.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageInputText(
  modelManagerViewModel: ModelManagerViewModel,
  curMessage: String,
  isResettingSession: Boolean,
  inProgress: Boolean,
  hasImageMessage: Boolean,
  modelInitializing: Boolean,
  @StringRes textFieldPlaceHolderRes: Int,
  onValueChanged: (String) -> Unit,
  onSendMessage: (List<ChatMessage>) -> Unit,
  modelPreparing: Boolean = false,
  onOpenPromptTemplatesClicked: () -> Unit = {},
  onStopButtonClicked: () -> Unit = {},
  showPromptTemplatesInMenu: Boolean = false,
  showImagePickerInMenu: Boolean = false,
  showStopButtonWhenInProgress: Boolean = false,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
  var showAddContentMenu by remember { mutableStateOf(false) }
  var showTextInputHistorySheet by remember { mutableStateOf(false) }
  var showCameraCaptureBottomSheet by remember { mutableStateOf(false) }
  val cameraCaptureSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  var tempPhotoUri by remember { mutableStateOf(value = Uri.EMPTY) }
  var pickedImages by remember { mutableStateOf<List<Bitmap>>(listOf()) }
  val updatePickedImages: (Bitmap) -> Unit = { bitmap ->
    val newPickedImages: MutableList<Bitmap> = mutableListOf()
    newPickedImages.addAll(pickedImages)
    newPickedImages.add(bitmap)
    pickedImages = newPickedImages.toList()
  }
  var hasFrontCamera by remember { mutableStateOf(false) }

  LaunchedEffect(Unit) {
    checkFrontCamera(context = context, callback = { hasFrontCamera = it })
  }

  // Permission request when taking picture.
  val takePicturePermissionLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestPermission()
  ) { permissionGranted ->
    if (permissionGranted) {
      showAddContentMenu = false
      tempPhotoUri = context.createTempPictureUri()
      showCameraCaptureBottomSheet = true
    }
  }

  // Registers a photo picker activity launcher in single-select mode.
  val pickMedia =
    rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
      // Callback is invoked after the user selects a media item or closes the
      // photo picker.
      if (uri != null) {
        handleImageSelected(context = context, uri = uri, onImageSelected = { bitmap ->
          updatePickedImages(bitmap)
        })
      }
    }

  Box(contentAlignment = Alignment.CenterStart) {
    // A preview panel for the selected image.
    if (pickedImages.isNotEmpty()) {
      Box(
        contentAlignment = Alignment.TopEnd, modifier = Modifier.offset(x = 16.dp, y = (-80).dp)
      ) {
        Image(
          bitmap = pickedImages.last().asImageBitmap(),
          contentDescription = "",
          modifier = Modifier
            .height(80.dp)
            .shadow(2.dp, shape = RoundedCornerShape(8.dp))
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp)),
        )
        Box(modifier = Modifier
          .offset(x = 10.dp, y = (-10).dp)
          .clip(CircleShape)
          .background(MaterialTheme.colorScheme.surface)
          .border((1.5).dp, MaterialTheme.colorScheme.outline, CircleShape)
          .clickable {
            pickedImages = listOf()
          }) {
          Icon(
            Icons.Rounded.Close,
            contentDescription = "",
            modifier = Modifier
              .padding(3.dp)
              .size(16.dp)
          )
        }
      }
    }

    // A plus button to show a popup menu to add stuff to the chat.
    IconButton(
      enabled = !inProgress && !isResettingSession,
      onClick = { showAddContentMenu = true },
      modifier = Modifier
        .offset(x = 16.dp)
        .alpha(0.8f)
    ) {
      Icon(
        Icons.Rounded.Add,
        contentDescription = "",
        modifier = Modifier.size(28.dp),
      )
    }
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(12.dp)
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(28.dp)),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      DropdownMenu(
        expanded = showAddContentMenu,
        onDismissRequest = { showAddContentMenu = false }) {
        if (showImagePickerInMenu) {
          // Take a picture.
          DropdownMenuItem(
            text = {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
              ) {
                Icon(Icons.Rounded.PhotoCamera, contentDescription = "")
                Text("Take a picture")
              }
            },
            enabled = pickedImages.isEmpty() && !hasImageMessage,
            onClick = {
              // Check permission
              when (PackageManager.PERMISSION_GRANTED) {
                // Already got permission. Call the lambda.
                ContextCompat.checkSelfPermission(
                  context, Manifest.permission.CAMERA
                ) -> {
                  showAddContentMenu = false
                  tempPhotoUri = context.createTempPictureUri()
                  showCameraCaptureBottomSheet = true
                }

                // Otherwise, ask for permission
                else -> {
                  takePicturePermissionLauncher.launch(Manifest.permission.CAMERA)
                }
              }
            })

          // Pick an image from album.
          DropdownMenuItem(
            text = {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
              ) {
                Icon(Icons.Rounded.Photo, contentDescription = "")
                Text("Pick from album")
              }
            },
            enabled = pickedImages.isEmpty() && !hasImageMessage,
            onClick = {
              // Launch the photo picker and let the user choose only images.
              pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
              showAddContentMenu = false
            })
        }

        // Prompt templates.
        if (showPromptTemplatesInMenu) {
          DropdownMenuItem(text = {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
              Icon(Icons.Rounded.PostAdd, contentDescription = "")
              Text("Prompt templates")
            }
          }, onClick = {
            onOpenPromptTemplatesClicked()
            showAddContentMenu = false
          })
        }
        // Prompt history.
        DropdownMenuItem(text = {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            Icon(Icons.Rounded.History, contentDescription = "")
            Text("Input history")
          }
        }, onClick = {
          showAddContentMenu = false
          showTextInputHistorySheet = true
        })
      }

      // Text field.
      TextField(value = curMessage,
        minLines = 1,
        maxLines = 3,
        onValueChange = onValueChanged,
        colors = TextFieldDefaults.colors(
          unfocusedContainerColor = Color.Transparent,
          focusedContainerColor = Color.Transparent,
          focusedIndicatorColor = Color.Transparent,
          unfocusedIndicatorColor = Color.Transparent,
          disabledIndicatorColor = Color.Transparent,
          disabledContainerColor = Color.Transparent,
        ),
        textStyle = MaterialTheme.typography.bodyLarge,
        modifier = Modifier
          .weight(1f)
          .padding(start = 36.dp),
        placeholder = { Text(stringResource(textFieldPlaceHolderRes)) })

      Spacer(modifier = Modifier.width(8.dp))

      if (inProgress && showStopButtonWhenInProgress) {
        if (!modelInitializing && !modelPreparing) {
          IconButton(
            onClick = onStopButtonClicked,
            colors = IconButtonDefaults.iconButtonColors(
              containerColor = MaterialTheme.colorScheme.secondaryContainer,
            ),
          ) {
            Icon(
              Icons.Rounded.Stop, contentDescription = "", tint = MaterialTheme.colorScheme.primary
            )
          }
        }
      } // Send button. Only shown when text is not empty.
      else if (curMessage.isNotEmpty()) {
        IconButton(
          enabled = !inProgress && !isResettingSession,
          onClick = {
            onSendMessage(
              createMessagesToSend(pickedImages = pickedImages, text = curMessage.trim())
            )
            pickedImages = listOf()
          },
          colors = IconButtonDefaults.iconButtonColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
          ),
        ) {
          Icon(
            Icons.AutoMirrored.Rounded.Send,
            contentDescription = "",
            modifier = Modifier.offset(x = 2.dp),
            tint = MaterialTheme.colorScheme.primary
          )
        }
      }
      Spacer(modifier = Modifier.width(4.dp))
    }
  }

  // A bottom sheet to show the text input history to pick from.
  if (showTextInputHistorySheet) {
    TextInputHistorySheet(
      history = modelManagerUiState.textInputHistory,
      onDismissed = {
        showTextInputHistorySheet = false
      },
      onHistoryItemClicked = { item ->
        onSendMessage(createMessagesToSend(pickedImages = pickedImages, text = item))
        pickedImages = listOf()
        modelManagerViewModel.promoteTextInputHistoryItem(item)
      },
      onHistoryItemDeleted = { item ->
        modelManagerViewModel.deleteTextInputHistory(item)
      },
      onHistoryItemsDeleteAll = {
        modelManagerViewModel.clearTextInputHistory()
      })
  }

  if (showCameraCaptureBottomSheet) {
    ModalBottomSheet(
      sheetState = cameraCaptureSheetState,
      onDismissRequest = { showCameraCaptureBottomSheet = false }) {

      val lifecycleOwner = LocalLifecycleOwner.current
      val previewUseCase = remember { androidx.camera.core.Preview.Builder().build() }
      val imageCaptureUseCase = remember {
        // Try to limit the image size.
        val preferredSize = Size(512, 512)
        val resolutionStrategy = ResolutionStrategy(
          preferredSize,
          ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
        )
        val resolutionSelector = ResolutionSelector.Builder()
          .setResolutionStrategy(resolutionStrategy)
          .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
          .build()

        ImageCapture.Builder().setResolutionSelector(resolutionSelector).build()
      }
      var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
      var cameraControl by remember { mutableStateOf<CameraControl?>(null) }
      val localContext = LocalContext.current
      var cameraSide by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
      val executor = remember { Executors.newSingleThreadExecutor() }

      fun rebindCameraProvider() {
        cameraProvider?.let { cameraProvider ->
          val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(cameraSide)
            .build()
          try {
            cameraProvider.unbindAll()
            val camera = cameraProvider.bindToLifecycle(
              lifecycleOwner = lifecycleOwner,
              cameraSelector = cameraSelector,
              previewUseCase,
              imageCaptureUseCase
            )
            cameraControl = camera.cameraControl
          } catch (e: Exception) {
            Log.d(TAG, "Failed to bind camera", e)
          }
        }
      }

      LaunchedEffect(Unit) {
        cameraProvider = ProcessCameraProvider.awaitInstance(localContext)
        rebindCameraProvider()
      }

      LaunchedEffect(cameraSide) {
        rebindCameraProvider()
      }

      DisposableEffect(Unit) { // Or key on lifecycleOwner if it makes more sense
        onDispose {
          cameraProvider?.unbindAll() // Unbind all use cases from the camera provider
          if (!executor.isShutdown) {
            executor.shutdown()     // Shut down the executor service
          }
        }
      }

      Box(modifier = Modifier.fillMaxSize()) {
        // PreviewView for the camera feed.
        AndroidView(
          modifier = Modifier.fillMaxSize(),
          factory = { ctx ->
            PreviewView(ctx).also {
              previewUseCase.surfaceProvider = it.surfaceProvider
              rebindCameraProvider()
            }
          },
        )

        // Close button.
        IconButton(
          onClick = {
            scope.launch {
              cameraCaptureSheetState.hide()
              showCameraCaptureBottomSheet = false
            }
          }, colors = IconButtonDefaults.iconButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
          ), modifier = Modifier
            .offset(x = (-8).dp, y = 8.dp)
            .align(Alignment.TopEnd)
        ) {
          Icon(
            Icons.Rounded.Close,
            contentDescription = "",
            tint = MaterialTheme.colorScheme.primary
          )
        }

        // Button that triggers the image capture process
        IconButton(
          colors = IconButtonDefaults.iconButtonColors(
            containerColor = MaterialTheme.colorScheme.primary,
          ),
          modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 32.dp)
            .size(64.dp)
            .border(2.dp, MaterialTheme.colorScheme.onPrimary, CircleShape),
          onClick = {
            val callback = object : ImageCapture.OnImageCapturedCallback() {
              override fun onCaptureSuccess(image: ImageProxy) {
                try {
                  var bitmap = image.toBitmap()
                  val rotation = image.imageInfo.rotationDegrees
                  bitmap = if (rotation != 0) {
                    val matrix = Matrix().apply {
                      postRotate(rotation.toFloat())
                    }
                    Log.d(TAG, "image size: ${bitmap.width}, ${bitmap.height}")
                    Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                  } else bitmap
                  updatePickedImages(bitmap)
                } catch (e: Exception) {
                  Log.e(TAG, "Failed to process image", e)
                } finally {
                  image.close()
                  scope.launch {
                    cameraCaptureSheetState.hide()
                    showCameraCaptureBottomSheet = false
                  }
                }
              }
            }
            imageCaptureUseCase.takePicture(executor, callback)
          },
        ) {
          Icon(
            Icons.Rounded.PhotoCamera,
            contentDescription = "",
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(36.dp)
          )
        }

        // Button that toggles the front and back camera.
        if (hasFrontCamera) {
          IconButton(
            colors = IconButtonDefaults.iconButtonColors(
              containerColor = MaterialTheme.colorScheme.secondaryContainer,
            ),
            modifier = Modifier
              .align(Alignment.BottomEnd)
              .padding(bottom = 40.dp, end = 32.dp)
              .size(48.dp),
            onClick = {
              cameraSide = when (cameraSide) {
                CameraSelector.LENS_FACING_BACK -> CameraSelector.LENS_FACING_FRONT
                else -> CameraSelector.LENS_FACING_BACK
              }
            },
          ) {
            Icon(
              Icons.Rounded.FlipCameraAndroid,
              contentDescription = "",
              tint = MaterialTheme.colorScheme.onSecondaryContainer,
              modifier = Modifier.size(24.dp)
            )
          }
        }
      }
    }
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
  val bitmap: Bitmap? = try {
    val inputStream = context.contentResolver.openInputStream(uri)
    val tmpBitmap = BitmapFactory.decodeStream(inputStream)
    rotateImageIfNecessary(bitmap = tmpBitmap, rotateForPortrait = rotateForPortrait)
  } catch (e: Exception) {
    e.printStackTrace()
    null
  }
  if (bitmap != null) {
    onImageSelected(bitmap)
  }
}

private fun rotateImageIfNecessary(bitmap: Bitmap, rotateForPortrait: Boolean = false): Bitmap {
  return if (rotateForPortrait && bitmap.width > bitmap.height) {
    val matrix = Matrix()
    matrix.postRotate(90f)
    Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
  } else {
    bitmap
  }
}

private fun checkFrontCamera(context: Context, callback: (Boolean) -> Unit) {
  val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
  cameraProviderFuture.addListener({
    val cameraProvider = cameraProviderFuture.get()
    try {
      // Attempt to select the default front camera
      val hasFront = cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
      callback(hasFront)
    } catch (e: Exception) {
      e.printStackTrace()
      callback(false)
    }
  }, ContextCompat.getMainExecutor(context))
}

private fun createMessagesToSend(pickedImages: List<Bitmap>, text: String): List<ChatMessage> {
  val messages: MutableList<ChatMessage> = mutableListOf()
  if (pickedImages.isNotEmpty()) {
    val lastImage = pickedImages.last()
    messages.add(
      ChatMessageImage(
        bitmap = lastImage, imageBitMap = lastImage.asImageBitmap(), side = ChatSide.USER
      )
    )
  }
  messages.add(
    ChatMessageText(
      content = text, side = ChatSide.USER
    )
  )

  return messages
}

@Preview(showBackground = true)
@Composable
fun MessageInputTextPreview() {
  val context = LocalContext.current

  GalleryTheme {
    Column {
      MessageInputText(
        modelManagerViewModel = PreviewModelManagerViewModel(context = context),
        curMessage = "hello",
        inProgress = false,
        isResettingSession = false,
        modelInitializing = false,
        hasImageMessage = false,
        textFieldPlaceHolderRes = R.string.chat_textinput_placeholder,
        onValueChanged = {},
        onSendMessage = {},
        showStopButtonWhenInProgress = true,
        showImagePickerInMenu = true,
      )
      MessageInputText(
        modelManagerViewModel = PreviewModelManagerViewModel(context = context),
        curMessage = "hello",
        inProgress = false,
        isResettingSession = false,
        hasImageMessage = false,
        modelInitializing = false,
        textFieldPlaceHolderRes = R.string.chat_textinput_placeholder,
        onValueChanged = {},
        onSendMessage = {},
        showStopButtonWhenInProgress = true,
      )
      MessageInputText(
        modelManagerViewModel = PreviewModelManagerViewModel(context = context),
        curMessage = "hello",
        inProgress = true,
        isResettingSession = false,
        hasImageMessage = false,
        modelInitializing = false,
        textFieldPlaceHolderRes = R.string.chat_textinput_placeholder,
        onValueChanged = {},
        onSendMessage = {},
      )
      MessageInputText(
        modelManagerViewModel = PreviewModelManagerViewModel(context = context),
        curMessage = "",
        inProgress = false,
        isResettingSession = false,
        hasImageMessage = false,
        modelInitializing = false,
        textFieldPlaceHolderRes = R.string.chat_textinput_placeholder,
        onValueChanged = {},
        onSendMessage = {},
      )
      MessageInputText(
        modelManagerViewModel = PreviewModelManagerViewModel(context = context),
        curMessage = "",
        inProgress = true,
        isResettingSession = false,
        hasImageMessage = false,
        modelInitializing = false,
        textFieldPlaceHolderRes = R.string.chat_textinput_placeholder,
        onValueChanged = {},
        onSendMessage = {},
        showStopButtonWhenInProgress = true,
      )
    }
  }
}


