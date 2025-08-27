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
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.Camera
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

private const val TAG = "VideoFrameCaptureButton"

/**
 * Calculate the correct camera display orientation based on device rotation and camera info.
 */
private fun getCameraDisplayOrientation(context: Context, cameraId: Int): Int {
  val cameraInfo = Camera.CameraInfo()
  Camera.getCameraInfo(cameraId, cameraInfo)
  
  val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
  val rotation = windowManager.defaultDisplay.rotation
  
  val degrees = when (rotation) {
    Surface.ROTATION_0 -> 0
    Surface.ROTATION_90 -> 90
    Surface.ROTATION_180 -> 180
    Surface.ROTATION_270 -> 270
    else -> 0
  }
  
  return if (cameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
    val result = (cameraInfo.orientation + degrees) % 360
    (360 - result) % 360 // compensate for mirror
  } else {
    // back-facing camera
    (cameraInfo.orientation - degrees + 360) % 360
  }
}

@Composable
fun VideoFrameCaptureButton(
  onFramesCaptured: (List<Bitmap>) -> Unit,
  enabled: Boolean = true,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  var showCaptureDialog by remember { mutableStateOf(false) }
  var isCapturing by remember { mutableStateOf(false) }
  var captureCount by remember { mutableStateOf(0) }
  val maxFrames = 10
  
  val cameraPermissionLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestPermission()
  ) { granted ->
    if (granted) {
      showCaptureDialog = true
    }
  }
  
  IconButton(
    onClick = {
      when (PackageManager.PERMISSION_GRANTED) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) -> {
          showCaptureDialog = true
        }
        else -> {
          cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
      }
    },
    enabled = enabled,
    colors = IconButtonDefaults.iconButtonColors(
      containerColor = MaterialTheme.colorScheme.primary
    ),
    modifier = modifier
  ) {
    Icon(
      imageVector = Icons.Default.Videocam,
      contentDescription = "Capture video frames",
      tint = MaterialTheme.colorScheme.onPrimary
    )
  }
  
  if (showCaptureDialog) {
    VideoFrameCaptureDialog(
      onDismiss = { 
        showCaptureDialog = false
        isCapturing = false
        captureCount = 0
      },
      onFramesCaptured = { frames ->
        onFramesCaptured(frames)
        showCaptureDialog = false
        isCapturing = false
        captureCount = 0
      },
      maxFrames = maxFrames
    )
  }
}

@Suppress("DEPRECATION") // Using legacy Camera API for compatibility
@Composable
private fun VideoFrameCaptureDialog(
  onDismiss: () -> Unit,
  onFramesCaptured: (List<Bitmap>) -> Unit,
  maxFrames: Int,
) {
  val context = LocalContext.current
  val coroutineScope = rememberCoroutineScope()
  
  var camera by remember { mutableStateOf<Camera?>(null) }
  var surfaceView by remember { mutableStateOf<SurfaceView?>(null) }
  var isCapturing by remember { mutableStateOf(false) }
  var captureCount by remember { mutableStateOf(0) }
  var capturedFrames by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
  var lastCaptureTime by remember { mutableStateOf(0L) }
  var cameraDisplayOrientation by remember { mutableStateOf(0) }
  
  val captureIntervalMs = 1000L // 1 FPS
  
  fun startCapture() {
    isCapturing = true
    captureCount = 0
    capturedFrames = emptyList()
    lastCaptureTime = System.currentTimeMillis()
  }
  
  fun stopCapture() {
    isCapturing = false
    camera?.setPreviewCallback(null)
  }
  
  fun initializeCamera() {
    try {
      val cameraId = 0 // Default to back camera
      cameraDisplayOrientation = getCameraDisplayOrientation(context, cameraId)
      
      camera = Camera.open(cameraId).apply {
        surfaceView?.holder?.let { holder ->
          setPreviewDisplay(holder)
          
          // Set the display orientation to match device orientation
          setDisplayOrientation(cameraDisplayOrientation)
          
          val parameters = this.parameters
          parameters?.let { params ->
            // Set preview format
            params.previewFormat = ImageFormat.NV21
            
            // Set preview size
            val supportedSizes = params.supportedPreviewSizes
            val targetSize = supportedSizes.find { it.width == 640 && it.height == 480 }
              ?: supportedSizes.firstOrNull()
            
            targetSize?.let {
              params.setPreviewSize(it.width, it.height)
            }
            
            this.parameters = params
          }
          
          setPreviewCallback { data, camera ->
            if (!isCapturing || captureCount >= maxFrames) return@setPreviewCallback
            
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastCaptureTime < captureIntervalMs) return@setPreviewCallback
            
            lastCaptureTime = currentTime
            
            coroutineScope.launch {
              try {
                val bitmap = convertFrameToBitmap(data, camera, cameraDisplayOrientation)
                bitmap?.let {
                  capturedFrames = capturedFrames + it
                  captureCount++
                  
                  if (captureCount >= maxFrames) {
                    stopCapture()
                    onFramesCaptured(capturedFrames)
                  }
                }
              } catch (e: Exception) {
                Log.e(TAG, "Error processing frame", e)
              }
            }
          }
          
          startPreview()
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to initialize camera", e)
    }
  }
  
  fun releaseCamera() {
    camera?.apply {
      setPreviewCallback(null)
      stopPreview()
      release()
    }
    camera = null
  }
  
  DisposableEffect(Unit) {
    onDispose {
      releaseCamera()
    }
  }
  
  Dialog(onDismissRequest = onDismiss) {
    Card(
      modifier = Modifier
        .fillMaxWidth()
        .wrapContentHeight()
    ) {
      Column(
        modifier = Modifier.padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        Text(
          text = "Video Frame Capture",
          style = MaterialTheme.typography.titleLarge,
          modifier = Modifier.padding(bottom = 16.dp)
        )
        
        // Camera preview
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
        ) {
          AndroidView(
            factory = { ctx ->
              SurfaceView(ctx).apply {
                surfaceView = this
                holder.addCallback(object : SurfaceHolder.Callback {
                  override fun surfaceCreated(holder: SurfaceHolder) {
                    initializeCamera()
                  }
                  
                  override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
                  
                  override fun surfaceDestroyed(holder: SurfaceHolder) {
                    releaseCamera()
                  }
                })
              }
            },
            modifier = Modifier.fillMaxSize()
          )
          
          // Overlay with capture info
          Card(
            modifier = Modifier
              .align(Alignment.BottomCenter)
              .padding(8.dp),
            colors = CardDefaults.cardColors(
              containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
            )
          ) {
            Text(
              text = if (isCapturing) "Capturing: $captureCount/$maxFrames" else "Ready to capture $maxFrames frames at 1 FPS",
              modifier = Modifier.padding(8.dp),
              style = MaterialTheme.typography.bodyMedium
            )
          }
        }
        
        if (isCapturing) {
          LinearProgressIndicator(
            progress = { captureCount.toFloat() / maxFrames.toFloat() },
            modifier = Modifier
              .fillMaxWidth()
              .padding(vertical = 8.dp),
          )
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Row(
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          OutlinedButton(onClick = onDismiss) {
            Text("Cancel")
          }
          
          Button(
            onClick = {
              if (isCapturing) {
                stopCapture()
              } else {
                startCapture()
              }
            },
            enabled = camera != null
          ) {
            Icon(
              imageVector = if (isCapturing) Icons.Default.Stop else Icons.Default.Videocam,
              contentDescription = null,
              modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(if (isCapturing) "Stop" else "Start")
          }
        }
      }
    }
  }
}

private fun convertFrameToBitmap(data: ByteArray, camera: Camera?, displayOrientation: Int): Bitmap? {
  return try {
    val parameters = camera?.parameters ?: return null
    val width = parameters.previewSize.width
    val height = parameters.previewSize.height
    
    val yuvImage = YuvImage(data, ImageFormat.NV21, width, height, null)
    val out = ByteArrayOutputStream()
    yuvImage.compressToJpeg(Rect(0, 0, width, height), 80, out)
    val imageBytes = out.toByteArray()
    
    var bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    
    // Apply rotation if needed to match the display orientation
    if (displayOrientation != 0 && bitmap != null) {
      val matrix = Matrix().apply {
        postRotate(displayOrientation.toFloat())
      }
      bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
    
    bitmap
  } catch (e: Exception) {
    Log.e(TAG, "Error converting frame to bitmap", e)
    null
  }
}