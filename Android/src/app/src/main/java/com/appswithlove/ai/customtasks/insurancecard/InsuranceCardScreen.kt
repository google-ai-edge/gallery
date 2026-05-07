package com.appswithlove.ai.customtasks.insurancecard

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.util.Log
import android.view.ViewGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.appswithlove.ai.R
import com.appswithlove.ai.data.ModelDownloadStatusType
import com.appswithlove.ai.data.Task
import com.appswithlove.ai.ui.llmchat.LlmChatModelHelper
import com.appswithlove.ai.ui.llmchat.LlmModelInstance
import com.appswithlove.ai.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlin.math.roundToInt
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import kotlinx.coroutines.launch

private const val TAG = "AGInsuranceCard"

/** ID-1 credit card aspect ratio (85.6mm / 54mm) used by Swiss KVG cards. */
private const val CARD_ASPECT_RATIO = 85.6f / 54f


private enum class ScanState {
  CAMERA,
  OCR_PROCESSING,
  LLM_PROCESSING,
  RESULT,
  ERROR,
}

@Composable
fun InsuranceCardScreen(
  task: Task,
  modelManagerViewModel: ModelManagerViewModel,
  bottomPadding: Dp,
) {
  val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
  val model = modelManagerUiState.selectedModel
  val curDownloadStatus = modelManagerUiState.modelDownloadStatus[model.name]?.status

  if (curDownloadStatus != ModelDownloadStatusType.SUCCEEDED ||
    !modelManagerUiState.isModelInitialized(model = model)
  ) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(
          trackColor = MaterialTheme.colorScheme.surfaceVariant,
          strokeWidth = 3.dp,
          modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text(
          stringResource(R.string.model_is_initializing_msg),
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
    return
  }

  CameraPermissionGate {
    InsuranceCardContent(
      modelManagerViewModel = modelManagerViewModel,
      bottomPadding = bottomPadding,
    )
  }
}

@Composable
private fun CameraPermissionGate(content: @Composable () -> Unit) {
  val context = LocalContext.current
  var permissionGranted by remember {
    mutableStateOf(
      ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED
    )
  }

  val launcher =
    androidx.activity.compose.rememberLauncherForActivityResult(
      androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
      permissionGranted = granted
    }

  if (permissionGranted) {
    content()
  } else {
    Column(
      modifier = Modifier.fillMaxSize().wrapContentSize().widthIn(max = 480.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Text(
        stringResource(R.string.insurance_camera_permission),
        textAlign = TextAlign.Center,
      )
      Spacer(Modifier.height(16.dp))
      Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) {
        Text(stringResource(R.string.grant_permissions))
      }
    }
  }
}

@Composable
private fun InsuranceCardContent(
  modelManagerViewModel: ModelManagerViewModel,
  bottomPadding: Dp,
) {
  val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
  val model = modelManagerUiState.selectedModel
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val scope = rememberCoroutineScope()

  var scanState by remember { mutableStateOf(ScanState.CAMERA) }
  var capturedBitmap by remember { mutableStateOf<Bitmap?>(null) }
  var ocrText by remember { mutableStateOf("") }
  var cardResult by remember { mutableStateOf<InsuranceCardResult?>(null) }
  var errorMessage by remember { mutableStateOf("") }

  val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
  val imageCapture = remember { ImageCapture.Builder().build() }
  val preview = remember { Preview.Builder().build() }
  var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

  // Track preview box size and overlay dimensions for accurate crop mapping
  var previewSizePx by remember { mutableStateOf(IntSize.Zero) }
  var overlayWidthPx by remember { mutableStateOf(0f) }
  var overlayHeightPx by remember { mutableStateOf(0f) }

  LaunchedEffect(Unit) {
    cameraProvider = ProcessCameraProvider.awaitInstance(context.applicationContext)
  }

  DisposableEffect(Unit) {
    onDispose {
      cameraProvider?.unbindAll()
      cameraExecutor.shutdown()
    }
  }

  fun resetToCamera() {
    // Reset LLM conversation to avoid prior card context leaking into next scan
    if (model.name != OCR_REGEX_MODEL.name && model.instance is LlmModelInstance) {
      val systemPrompt = if (model.llmSupportImage) IMAGE_SYSTEM_PROMPT else TEXT_SYSTEM_PROMPT
      LlmChatModelHelper.resetConversation(
        model = model,
        supportImage = model.llmSupportImage,
        supportAudio = false,
        systemInstruction = Contents.of(systemPrompt),
      )
    }
    scanState = ScanState.CAMERA
    capturedBitmap = null
    ocrText = ""
    cardResult = null
    errorMessage = ""
  }

  fun captureAndProcess() {
    val isOcrMode = model.name == OCR_REGEX_MODEL.name
    scanState = if (isOcrMode || !model.llmSupportImage) ScanState.OCR_PROCESSING else ScanState.LLM_PROCESSING
    imageCapture.takePicture(
      cameraExecutor,
      object : ImageCapture.OnImageCapturedCallback() {
        @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
        override fun onCaptureSuccess(image: ImageProxy) {
          try {
            val bitmap = imageProxyToBitmap(image)
            val rotationDegrees = image.imageInfo.rotationDegrees
            image.close()

            Log.d(TAG, "Captured: ${bitmap.width}x${bitmap.height}, rotation=$rotationDegrees")

            val rotated = bitmap.rotate(rotationDegrees.toFloat())
            val displayCropped = cropToOverlayRegion(rotated, previewSizePx, overlayWidthPx, overlayHeightPx)

            scope.launch {
              capturedBitmap = displayCropped

              when {
                // OCR + Regex mode — current behavior
                isOcrMode -> {
                  recognizeText(
                    bitmap, rotationDegrees,
                    onSuccess = { text ->
                      ocrText = text
                      Log.d(TAG, "OCR text: $text")
                      if (text.isBlank()) {
                        errorMessage = context.getString(R.string.insurance_no_text_recognized)
                        scanState = ScanState.ERROR
                        return@recognizeText
                      }
                      cardResult = extractFieldsFromOcr(text)
                      scanState = ScanState.RESULT
                    },
                    onFailure = { e ->
                      scope.launch {
                        errorMessage = context.getString(R.string.insurance_ocr_failed, e.message ?: "")
                        scanState = ScanState.ERROR
                      }
                    },
                  )
                }

                // Multimodal LLM — send image directly
                model.llmSupportImage -> {
                  scope.launch(Dispatchers.Default) {
                    try {
                      val instance = model.instance as LlmModelInstance
                      val pngBytes = displayCropped.toPngByteArray()
                      val contents = Contents.of(
                        Content.ImageBytes(pngBytes),
                        Content.Text("Extract all fields from this Swiss insurance card image."),
                      )
                      val response = instance.conversation.sendMessage(contents)
                      val result = InsuranceCardLlmParser.parse(response.toString())
                      scope.launch {
                        if (result != null) {
                          cardResult = result
                          scanState = ScanState.RESULT
                        } else {
                          Log.w(TAG, "LLM response could not be parsed: $response")
                          errorMessage = context.getString(R.string.insurance_llm_parse_error)
                          scanState = ScanState.ERROR
                        }
                      }
                    } catch (e: Exception) {
                      Log.e(TAG, "LLM inference failed", e)
                      scope.launch {
                        errorMessage = context.getString(R.string.insurance_llm_inference_failed, e.message ?: "")
                        scanState = ScanState.ERROR
                      }
                    }
                  }
                }

                // Text-only LLM — OCR first, then LLM extraction
                else -> {
                  recognizeText(
                    bitmap, rotationDegrees,
                    onSuccess = { text ->
                      ocrText = text
                      Log.d(TAG, "OCR text: $text")
                      if (text.isBlank()) {
                        errorMessage = context.getString(R.string.insurance_no_text_recognized)
                        scanState = ScanState.ERROR
                        return@recognizeText
                      }
                      scanState = ScanState.LLM_PROCESSING
                      scope.launch(Dispatchers.Default) {
                        try {
                          val instance = model.instance as LlmModelInstance
                          val contents = Contents.of(Content.Text(text))
                          val response = instance.conversation.sendMessage(contents)
                          val result = InsuranceCardLlmParser.parse(response.toString())
                          scope.launch {
                            // Fall back to regex if LLM parse fails
                            cardResult = result ?: extractFieldsFromOcr(text)
                            scanState = ScanState.RESULT
                          }
                        } catch (e: Exception) {
                          Log.e(TAG, "LLM inference failed", e)
                          scope.launch {
                            errorMessage = "LLM inference failed: ${e.message}"
                            scanState = ScanState.ERROR
                          }
                        }
                      }
                    },
                    onFailure = { e ->
                      scope.launch {
                        errorMessage = context.getString(R.string.insurance_ocr_failed, e.message ?: "")
                        scanState = ScanState.ERROR
                      }
                    },
                  )
                }
              }
            }
          } catch (e: Exception) {
            image.close()
            scope.launch {
              errorMessage = context.getString(R.string.insurance_capture_processing_failed, e.message ?: "")
              scanState = ScanState.ERROR
            }
          }
        }

        override fun onError(exception: ImageCaptureException) {
          Log.e(TAG, "Capture failed", exception)
          scope.launch {
            errorMessage = context.getString(R.string.insurance_capture_failed, exception.message ?: "")
            scanState = ScanState.ERROR
          }
        }
      },
    )
  }

  Column(
    modifier = Modifier.fillMaxSize().padding(bottom = bottomPadding),
  ) {
    when (scanState) {
      ScanState.CAMERA -> {
        Box(modifier = Modifier.weight(1f).onGloballyPositioned { previewSizePx = it.size }) {
          CameraPreview(
            preview = preview,
            imageCapture = imageCapture,
            cameraProvider = cameraProvider,
            lifecycleOwner = lifecycleOwner,
          )
          // Card overlay guide — matches crop region
          val density = LocalDensity.current
          overlayWidthPx = previewSizePx.width - with(density) { 64.dp.toPx() }
          overlayHeightPx = overlayWidthPx / CARD_ASPECT_RATIO
          val overlayWidthDp = with(density) { overlayWidthPx.toDp() }
          val overlayHeightDp = with(density) { overlayHeightPx.toDp() }
          Box(
            modifier =
              Modifier.align(Alignment.Center)
                .width(overlayWidthDp)
                .height(overlayHeightDp)
                .border(2.dp, MaterialTheme.colorScheme.primary),
          )
        }
        Box(
          modifier = Modifier.fillMaxWidth().padding(16.dp),
          contentAlignment = Alignment.Center,
        ) {
          FloatingActionButton(
            onClick = { captureAndProcess() },
            shape = CircleShape,
          ) {
            Icon(
              imageVector = Icons.Default.Camera,
              contentDescription = stringResource(R.string.insurance_capture),
            )
          }
        }
      }

      ScanState.OCR_PROCESSING -> {
        ProcessingView(
          bitmap = capturedBitmap,
          statusText = stringResource(R.string.insurance_scanning),
        )
      }

      ScanState.LLM_PROCESSING -> {
        ProcessingView(
          bitmap = capturedBitmap,
          statusText = stringResource(R.string.insurance_analyzing),
        )
      }

      ScanState.RESULT -> {
        ResultView(
          result = cardResult,
          bitmap = capturedBitmap,
          onRetake = { resetToCamera() },
        )
      }

      ScanState.ERROR -> {
        ErrorView(
          message = errorMessage,
          onRetake = { resetToCamera() },
        )
      }
    }
  }
}

@Composable
private fun CameraPreview(
  preview: Preview,
  imageCapture: ImageCapture,
  cameraProvider: ProcessCameraProvider?,
  lifecycleOwner: androidx.lifecycle.LifecycleOwner,
) {
  AndroidView(
    modifier = Modifier.fillMaxSize(),
    factory = { ctx ->
      PreviewView(ctx).apply {
        layoutParams =
          ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
          )
        scaleType = PreviewView.ScaleType.FILL_CENTER
      }
    },
    update = { previewView ->
      cameraProvider?.let { provider ->
        try {
          provider.unbindAll()
          preview.surfaceProvider = previewView.surfaceProvider
          provider.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            preview,
            imageCapture,
          )
        } catch (e: Exception) {
          Log.e(TAG, "Camera bind failed", e)
        }
      }
    },
  )
}

@Composable
private fun ProcessingView(bitmap: Bitmap?, statusText: String) {
  Column(
    modifier = Modifier.fillMaxSize(),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    bitmap?.let {
      Image(
        bitmap = it.asImageBitmap(),
        contentDescription = null,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp),
        contentScale = ContentScale.FillWidth,
      )
      Spacer(Modifier.height(24.dp))
    }
    CircularProgressIndicator(
      trackColor = MaterialTheme.colorScheme.surfaceVariant,
      strokeWidth = 3.dp,
      modifier = Modifier.size(32.dp),
    )
    Spacer(Modifier.height(12.dp))
    Text(statusText, style = MaterialTheme.typography.bodyLarge)
  }
}

@Composable
private fun ResultView(
  result: InsuranceCardResult?,
  bitmap: Bitmap?,
  onRetake: () -> Unit,
) {
  Column(
    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    bitmap?.let {
      Image(
        bitmap = it.asImageBitmap(),
        contentDescription = null,
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        contentScale = ContentScale.FillWidth,
      )
    }

    result?.let { r ->
      val validation = remember(r) { InsuranceCardValidator.validate(r) }

      // Overall status
      Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        colors = CardDefaults.cardColors(
          containerColor = if (validation.allValid)
            MaterialTheme.colorScheme.primaryContainer
          else
            MaterialTheme.colorScheme.errorContainer
        ),
      ) {
        Row(
          modifier = Modifier.padding(12.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Icon(
            if (validation.allValid) Icons.Default.CheckCircle else Icons.Default.Warning,
            contentDescription = null,
            tint = if (validation.allValid)
              MaterialTheme.colorScheme.primary
            else
              MaterialTheme.colorScheme.error,
          )
          Spacer(Modifier.width(8.dp))
          Text(
            if (validation.allValid) stringResource(R.string.insurance_validation_all_valid)
            else stringResource(R.string.insurance_validation_some_invalid),
            style = MaterialTheme.typography.titleSmall,
          )
        }
      }

      Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
      ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(
            stringResource(R.string.insurance_task_label),
            style = MaterialTheme.typography.titleMedium,
          )
          Spacer(Modifier.height(4.dp))
          ValidatedFieldRow(stringResource(R.string.insurance_field_versicherer), r.versicherer, validation.versicherer)
          ValidatedFieldRow(stringResource(R.string.insurance_field_name), r.name, validation.name)
          ValidatedFieldRow(stringResource(R.string.insurance_field_vorname), r.vorname, validation.vorname)
          ValidatedFieldRow(stringResource(R.string.insurance_field_geburtsdatum), r.geburtsdatum, validation.geburtsdatum)
          ValidatedFieldRow(stringResource(R.string.insurance_field_versichertennummer), r.versichertennummer, validation.versichertennummer)
          ValidatedFieldRow(stringResource(R.string.insurance_field_ahv_nummer), r.ahvNummer, validation.ahvNummer)
          ValidatedFieldRow(stringResource(R.string.insurance_field_kartennummer), r.kartenNummer, validation.kartenNummer)
        }
      }
    }

    Spacer(Modifier.height(16.dp))
    TextButton(onClick = onRetake) { Text(stringResource(R.string.insurance_retake)) }
  }
}

@Composable
private fun ValidatedFieldRow(label: String, value: String, validation: FieldValidation) {
  if (value.isBlank() && validation.isValid) return // skip empty optional fields
  Column(modifier = Modifier.fillMaxWidth()) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
      Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(value.ifBlank { "-" }, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(6.dp))
        Icon(
          imageVector = when {
            !validation.isValid -> Icons.Default.Error
            validation.message.isNotEmpty() -> Icons.Default.Warning
            else -> Icons.Default.CheckCircle
          },
          contentDescription = null,
          modifier = Modifier.size(16.dp),
          tint = when {
            !validation.isValid -> MaterialTheme.colorScheme.error
            validation.message.isNotEmpty() -> MaterialTheme.colorScheme.tertiary
            else -> MaterialTheme.colorScheme.primary
          },
        )
      }
    }
    if (validation.message.isNotEmpty()) {
      Text(
        validation.message,
        style = MaterialTheme.typography.bodySmall,
        color = if (validation.isValid) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(top = 2.dp),
      )
    }
  }
}

@Composable
private fun ErrorView(message: String, onRetake: () -> Unit) {
  Column(
    modifier = Modifier.fillMaxSize().wrapContentSize(),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text(stringResource(R.string.error), style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    Text(message, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 32.dp))
    Spacer(Modifier.height(16.dp))
    Button(onClick = onRetake) { Text(stringResource(R.string.insurance_retake)) }
  }
}

// --- Utility functions ---

private fun recognizeText(
  bitmap: Bitmap,
  rotationDegrees: Int = 0,
  onSuccess: (String) -> Unit,
  onFailure: (Exception) -> Unit,
) {
  val inputImage = InputImage.fromBitmap(bitmap, rotationDegrees)
  val recognizer = TextRecognition.getClient(TextRecognizerOptions.Builder().build())
  recognizer
    .process(inputImage)
    .addOnSuccessListener { visionText -> onSuccess(visionText.text) }
    .addOnFailureListener { e -> onFailure(e) }
}

private fun imageProxyToBitmap(image: ImageProxy): Bitmap {
  if (image.format == ImageFormat.JPEG && image.planes.size == 1) {
    val buffer = image.planes[0].buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
  }
  if (image.format == ImageFormat.YUV_420_888 && image.planes.size == 3) {
    val yBuffer = image.planes[0].buffer
    val uBuffer = image.planes[1].buffer
    val vBuffer = image.planes[2].buffer
    val ySize = yBuffer.remaining()
    val uSize = uBuffer.remaining()
    val vSize = vBuffer.remaining()
    val nv21 = ByteArray(ySize + uSize + vSize)
    yBuffer.get(nv21, 0, ySize)
    vBuffer.get(nv21, ySize, vSize)
    uBuffer.get(nv21, ySize + vSize, uSize)
    val yuvImage = android.graphics.YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
    val out = ByteArrayOutputStream()
    yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 100, out)
    val yuvBytes = out.toByteArray()
    return android.graphics.BitmapFactory.decodeByteArray(yuvBytes, 0, yuvBytes.size)
  }
  throw IllegalArgumentException("Unsupported ImageProxy format: ${image.format}")
}

/**
 * Crops the camera image to match the overlay rectangle shown on the preview.
 * Maps preview coordinates → camera image coordinates using FILL_CENTER scaling.
 * Adds 10% padding so OCR captures surrounding context.
 */
private fun cropToOverlayRegion(
  bitmap: Bitmap,
  previewSizePx: IntSize,
  overlayViewW: Float,
  overlayViewH: Float,
): Bitmap {
  if (previewSizePx.width == 0 || previewSizePx.height == 0) return bitmap

  val imgW = bitmap.width.toFloat()
  val imgH = bitmap.height.toFloat()
  val viewW = previewSizePx.width.toFloat()
  val viewH = previewSizePx.height.toFloat()

  // FILL_CENTER: scale so image fills the preview, cropping overflow
  val scale = max(viewW / imgW, viewH / imgH)

  // Part of the image visible in the preview (in image coords)
  val visibleImgW = viewW / scale
  val visibleImgH = viewH / scale
  val offsetX = (imgW - visibleImgW) / 2f
  val offsetY = (imgH - visibleImgH) / 2f

  // Overlay is centered in the preview
  val overlayViewLeft = (viewW - overlayViewW) / 2f
  val overlayViewTop = (viewH - overlayViewH) / 2f

  // Map to image coords
  val cropX = offsetX + overlayViewLeft / scale
  val cropY = offsetY + overlayViewTop / scale
  val cropW = overlayViewW / scale
  val cropH = overlayViewH / scale

  // Add 10% padding for better OCR context
  val padX = cropW * 0.10f
  val padY = cropH * 0.10f

  val left = (cropX - padX).roundToInt().coerceIn(0, bitmap.width - 1)
  val top = (cropY - padY).roundToInt().coerceIn(0, bitmap.height - 1)
  val w = (cropW + padX * 2).roundToInt().coerceIn(1, bitmap.width - left)
  val h = (cropH + padY * 2).roundToInt().coerceIn(1, bitmap.height - top)

  return Bitmap.createBitmap(bitmap, left, top, w, h)
}

private fun Bitmap.rotate(degrees: Float): Bitmap {
  if (degrees == 0f) return this
  val matrix = Matrix()
  matrix.postRotate(degrees)
  return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
}

private fun Bitmap.toPngByteArray(): ByteArray {
  val stream = ByteArrayOutputStream()
  compress(Bitmap.CompressFormat.PNG, 100, stream)
  return stream.toByteArray()
}


/**
 * Extract insurance card fields from OCR text.
 * Tries EHIC back-of-card numbered format first (fields 3-8), then front-of-card labels.
 */
private fun extractFieldsFromOcr(text: String): InsuranceCardResult {
  val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }

  // --- Pattern-based extraction (works for both sides) ---

  // AHV/OASI: 756.XXXX.XXXX.XX (13 digits starting with 756)
  val ahvRaw = Regex("""756[\.\s/-]?\d{4}[\.\s/-]?\d{4}[\.\s/-]?\d{2}""").find(text)?.value ?: ""
  val ahvDigits = ahvRaw.replace(Regex("""[^\d]"""), "")
  val ahvNummer = if (ahvDigits.length == 13)
    "${ahvDigits.substring(0,3)}.${ahvDigits.substring(3,7)}.${ahvDigits.substring(7,11)}.${ahvDigits.substring(11)}"
  else ""

  // Birth date: DD.MM.YYYY or DD/MM/YYYY, must be in the past
  val now = LocalDate.now()
  val geburtsdatum = Regex("""\d{1,2}[./]\d{1,2}[./]\d{4}""").findAll(text)
    .map { it.value }
    .firstOrNull { dateStr ->
      try {
        val normalized = dateStr.replace('/', '.')
        val date = LocalDate.parse(normalized, DateTimeFormatter.ofPattern("d.M.yyyy"))
        date.isBefore(now) && date.isAfter(now.minusYears(150))
      } catch (_: Exception) { false }
    }?.replace('/', '.') ?: ""

  // Card number: 20-digit (80756... or 8231...)
  val kartenNummer = Regex("""\b\d{20}\b""").find(text)?.value ?: ""

  // Field 7 on EHIC back: "01234 - Sanitas" → BAG-Nr + Versicherer
  val field7Match = Regex("""(\d{5})\s*-\s*([A-Za-zÀ-ÿ][\w\s]*)""").find(text)
  val bagNr = field7Match?.groupValues?.get(1) ?: ""
  val insurerFromField7 = field7Match?.groupValues?.get(2)?.trim() ?: ""

  // Insurer: match field 7 value against official BAG list, then try full text
  val versicherer = matchInsurer(insurerFromField7) ?: matchInsurer(text) ?: ""

  // --- Name extraction ---
  // --- Name extraction ---
  // Strategy: find ALL-CAPS, letters-only text that isn't a label or known value.
  // On EHIC back, names are printed in caps. First match = Name, second = Vorname.
  val nameCandidates = lines.mapNotNull { line ->
    val cleaned = line
      .replace(Regex("""\d{1,2}[./]\d{1,2}[./]\d{4}"""), "") // strip dates
      .replace(Regex("""\d[\d.\s/-]*"""), "")                  // strip number sequences
      .replace(Regex("""[^\p{L}\s'-]"""), "")                  // keep only name chars
      .trim()
    if (cleaned.length >= 3 &&
      cleaned == cleaned.uppercase() &&
      !isLabelLine(cleaned) &&
      matchInsurer(cleaned) == null &&
      cleaned !in SHORT_CODES
    ) cleaned else null
  }
  val backName = nameCandidates.getOrElse(0) { "" }
  val backVorname = nameCandidates.getOrElse(1) { "" }

  // Front of card fallback: label-based (for cards without ALL-CAPS names)
  val nameSkipWords = listOf("name", "nom", "cognome", "prénom", "prenom", "vorname", "nome", "first", "last")
  val fullNameFront = if (backName.isBlank()) valueAfterLabel(lines, listOf("Name", "Nom"), nameSkipWords) else ""
  val (frontName, frontVorname) = splitNameVorname(fullNameFront)

  val name = backName.ifBlank { frontName }.cleanName()
  val vorname = backVorname.ifBlank { frontVorname }.cleanName()

  // Versichertennummer: BAG-Nr from field 7, or front label
  val versichertennummer = bagNr.ifBlank {
    valueAfterLabel(lines,
      listOf("Versicherten-Nr", "Vers.Nr", "Vers.-Nr", "No d'assuré", "N. assicurato"),
      listOf("versicherten", "assuré", "assicurato", "vers."))
  }

  return InsuranceCardResult(
    name = name,
    vorname = vorname,
    geburtsdatum = geburtsdatum,
    versichertennummer = versichertennummer,
    ahvNummer = ahvNummer,
    versicherer = versicherer.cleanName(),
    kartenNummer = kartenNummer,
  )
}

/**
 * EHIC back-of-card: finds value line after a numbered field label (e.g. "3. Name").
 * Skips lines that are numbered labels or contain known EHIC label words.
 */
private fun numberedFieldValue(lines: List<String>, fieldNum: Int): String {
  val labelRegex = Regex("""(^|\s)$fieldNum\.?\s""")
  for (i in lines.indices) {
    if (labelRegex.containsMatchIn(lines[i])) {
      for (j in i + 1 until lines.size) {
        val candidate = lines[j]
        if (isLabelLine(candidate)) continue
        return candidate
      }
    }
  }
  return ""
}


/** Find the best-matching insurer from the official BAG list in the given text. */
private fun matchInsurer(text: String): String? {
  if (text.isBlank()) return null
  val lower = text.lowercase()
  // Try longest match first (avoids "AMB" matching inside other words)
  return KNOWN_INSURERS.sortedByDescending { it.length }
    .firstOrNull { lower.contains(it.lowercase()) }
}

private fun isLabelLine(line: String): Boolean {
  // Numbered field label (e.g. "5. Geburtsdatum")
  if (Regex("""(^|\s)\d+\.?\s[A-Za-zÀ-ÿ]""").containsMatchIn(line)) return true
  // Known EHIC / KVG label words
  val lower = line.lowercase()
  return LABEL_WORDS.any { lower.contains(it) }
}

private val LABEL_WORDS = listOf(
  "name", "vornamen", "vorname", "geburtsdatum", "kennummer", "kennnummer",
  "persönliche", "träger", "karte", "naissance", "prénom", "prenom",
  "nom", "numéro", "identification", "institution", "cognome", "nome",
  "gültig", "valable", "valido", "expiry", "ablauf", "versicherten",
  "carte", "assicurato", "assuré", "date de",
  // EHIC card header words
  "europäische", "krankenversicherung", "versicherung", "versichertenkarte",
  "assurance", "maladie", "health", "insurance", "card", "tessera",
  "européenne", "europea", "european",
  // Country / legal terms
  "schweiz", "suisse", "svizzera", "switzerland",
  "kvg", "lamal", "bag", "ehic", "ceam",
)

/** Short ALL-CAPS codes that appear on cards but aren't names. */
private val SHORT_CODES = setOf(
  "CH", "EU", "DE", "AT", "FR", "IT", "LI",
  "KVG", "BAG", "AHV", "AVS", "EHIC", "CEAM",
)

/** Finds value on the line AFTER a label line, skipping translation lines. */
private fun valueAfterLabel(
  lines: List<String>,
  labelKeywords: List<String>,
  skipKeywords: List<String>,
): String {
  for (i in lines.indices) {
    if (labelKeywords.any { lines[i].contains(it, ignoreCase = true) }) {
      for (j in i + 1 until lines.size) {
        val candidate = lines[j]
        if (skipKeywords.none { candidate.contains(it, ignoreCase = true) }) {
          return candidate
        }
      }
    }
  }
  return ""
}

/** Strip digits and convert "HANS" → "Hans", "MÜLLER-SCHMIDT" → "Müller-Schmidt". */
private fun String.cleanName(): String {
  val stripped = replace(Regex("""\d"""), "").trim()
  if (stripped.isBlank()) return ""
  val sb = StringBuilder()
  var capitalize = true
  for (c in stripped.lowercase()) {
    sb.append(if (capitalize && c.isLetter()) c.uppercaseChar() else c)
    capitalize = c == ' ' || c == '-'
  }
  return sb.toString()
}

/** Split "Muster, Max" or "Muster Max" into (name, vorname). */
private fun splitNameVorname(fullName: String): Pair<String, String> {
  if (fullName.isBlank()) return "" to ""
  val parts = if (fullName.contains(",")) {
    fullName.split(",", limit = 2).map { it.trim() }
  } else {
    fullName.split(" ", limit = 2).map { it.trim() }
  }
  return parts.getOrElse(0) { "" } to parts.getOrElse(1) { "" }
}

/** Official BAG (Bundesamt für Gesundheit) list of KVG insurers + common brand names. */
private val KNOWN_INSURERS = listOf(
  // Full official names (longest first for matching priority)
  "Groupe Mutuel", "Mutuel Assurance", "Luzerner Hinterland",
  "Vita Surselva", "Easy Sana",
  // Common brand names as printed on cards
  "Agrisano", "Aquilana", "Assura", "Atupri", "Avenir",
  "Birchmeier", "Compact", "Concordia", "CSS", "Curaulta",
  "EGK", "Einsiedler", "Galenos", "Glarner", "Helsana", "Hotela",
  "Innova", "Kolping", "KPT", "Metallvita",
  "Mutuelle Neuchâteloise", "ÖKK", "Philos", "Rhenusana",
  "Sana24", "Sanagate", "Sanitas", "SLKK", "Sodalis",
  "Steffisburg", "Sumiswalder", "Supra", "Swica", "Sympany",
  "Visana", "Visperterminen", "Vivao", "Wädenswil", "AMB",
)
