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

package com.google.ai.edge.gallery.common

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.exifinterface.media.ExifInterface
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.SAMPLE_RATE
import com.google.ai.edge.gallery.ui.common.chat.ChatMessage
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageBenchmarkLlmResult
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageText
import com.google.ai.edge.gallery.ui.common.chat.ChatSide
import com.google.gson.Gson
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import android.graphics.Path
import kotlin.math.log


private const val TAG = "AGUtils"

fun cleanUpMediapipeTaskErrorMessage(message: String): String {
  val index = message.indexOf("=== Source Location Trace")
  if (index >= 0) {
    return message.substring(0, index)
  }
  return message
}

fun processLlmResponse(response: String): String {
  return response.replace("\\n", "\n")
}

inline fun <reified T> getJsonResponse(url: String): JsonObjAndTextContent<T>? {
  try {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.requestMethod = "GET"
    connection.connect()

    val responseCode = connection.responseCode
    if (responseCode == HttpURLConnection.HTTP_OK) {
      val inputStream = connection.inputStream
      val response = inputStream.bufferedReader().use { it.readText() }

      val gson = Gson()
      val jsonObj = gson.fromJson(response, T::class.java)
      return JsonObjAndTextContent(jsonObj = jsonObj, textContent = response)
    } else {
      Log.e("AGUtils", "HTTP error: $responseCode")
    }
  } catch (e: Exception) {
    Log.e("AGUtils", "Error when getting json response: ${e.message}")
    e.printStackTrace()
  }

  return null
}

fun convertWavToMonoWithMaxSeconds(
  context: Context,
  stereoUri: Uri,
  maxSeconds: Int = 30,
): AudioClip? {
  Log.d(TAG, "Start to convert wav file to mono channel")

  try {
    val inputStream = context.contentResolver.openInputStream(stereoUri) ?: return null
    val originalBytes = inputStream.readBytes()
    inputStream.close()

    // Read WAV header
    if (originalBytes.size < 44) {
      // Not a valid WAV file
      Log.e(TAG, "Not a valid wav file")
      return null
    }

    val headerBuffer = ByteBuffer.wrap(originalBytes, 0, 44).order(ByteOrder.LITTLE_ENDIAN)
    val channels = headerBuffer.getShort(22)
    var sampleRate = headerBuffer.getInt(24)
    val bitDepth = headerBuffer.getShort(34)
    Log.d(TAG, "File metadata: channels: $channels, sampleRate: $sampleRate, bitDepth: $bitDepth")

    // Normalize audio to 16-bit.
    val audioDataBytes = originalBytes.copyOfRange(fromIndex = 44, toIndex = originalBytes.size)
    var sixteenBitBytes: ByteArray =
      if (bitDepth.toInt() == 8) {
        Log.d(TAG, "Converting 8-bit audio to 16-bit.")
        convert8BitTo16Bit(audioDataBytes)
      } else {
        // Assume 16-bit or other format that can be handled directly
        audioDataBytes
      }

    // Convert byte array to short array for processing
    val shortBuffer =
      ByteBuffer.wrap(sixteenBitBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
    var pcmSamples = ShortArray(shortBuffer.remaining())
    shortBuffer.get(pcmSamples)

    // Resample if sample rate is less than 16000 Hz ---
    if (sampleRate < SAMPLE_RATE) {
      Log.d(TAG, "Resampling from $sampleRate Hz to $SAMPLE_RATE Hz.")
      pcmSamples = resample(pcmSamples, sampleRate, SAMPLE_RATE, channels.toInt())
      sampleRate = SAMPLE_RATE
      Log.d(TAG, "Resampling complete. New sample count: ${pcmSamples.size}")
    }

    // Convert stereo to mono if necessary
    var monoSamples =
      if (channels.toInt() == 2) {
        Log.d(TAG, "Converting stereo to mono.")
        val mono = ShortArray(pcmSamples.size / 2)
        for (i in mono.indices) {
          val left = pcmSamples[i * 2]
          val right = pcmSamples[i * 2 + 1]
          mono[i] = ((left + right) / 2).toShort()
        }
        mono
      } else {
        Log.d(TAG, "Audio is already mono. No channel conversion needed.")
        pcmSamples
      }

    // Trim the audio to maxSeconds ---
    val maxSamples = maxSeconds * sampleRate
    if (monoSamples.size > maxSamples) {
      Log.d(TAG, "Trimming clip from ${monoSamples.size} samples to $maxSamples samples.")
      monoSamples = monoSamples.copyOfRange(0, maxSamples)
    }

    val monoByteBuffer = ByteBuffer.allocate(monoSamples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
    monoByteBuffer.asShortBuffer().put(monoSamples)
    return AudioClip(audioData = monoByteBuffer.array(), sampleRate = sampleRate)
  } catch (e: Exception) {
    Log.e(TAG, "Failed to convert wav to mono", e)
    return null
  }
}

/** Converts 8-bit unsigned PCM audio data to 16-bit signed PCM. */
private fun convert8BitTo16Bit(eightBitData: ByteArray): ByteArray {
  // The new 16-bit data will be twice the size
  val sixteenBitData = ByteArray(eightBitData.size * 2)
  val buffer = ByteBuffer.wrap(sixteenBitData).order(ByteOrder.LITTLE_ENDIAN)

  for (byte in eightBitData) {
    // Convert the unsigned 8-bit byte (0-255) to a signed 16-bit short (-32768 to 32767)
    // 1. Get the unsigned value by masking with 0xFF
    // 2. Subtract 128 to center the waveform around 0 (range becomes -128 to 127)
    // 3. Scale by 256 to expand to the 16-bit range
    val unsignedByte = byte.toInt() and 0xFF
    val sixteenBitSample = ((unsignedByte - 128) * 256).toShort()
    buffer.putShort(sixteenBitSample)
  }
  return sixteenBitData
}

/** Resamples PCM audio data from an original sample rate to a target sample rate. */
private fun resample(
  inputSamples: ShortArray,
  originalSampleRate: Int,
  targetSampleRate: Int,
  channels: Int,
): ShortArray {
  if (originalSampleRate == targetSampleRate) {
    return inputSamples
  }

  val ratio = targetSampleRate.toDouble() / originalSampleRate
  val outputLength = (inputSamples.size * ratio).toInt()
  val resampledData = ShortArray(outputLength)

  if (channels == 1) { // Mono
    for (i in resampledData.indices) {
      val position = i / ratio
      val index1 = floor(position).toInt()
      val index2 = index1 + 1
      val fraction = position - index1

      val sample1 = if (index1 < inputSamples.size) inputSamples[index1].toDouble() else 0.0
      val sample2 = if (index2 < inputSamples.size) inputSamples[index2].toDouble() else 0.0

      resampledData[i] = (sample1 * (1 - fraction) + sample2 * fraction).toInt().toShort()
    }
  }

  return resampledData
}

fun calculatePeakAmplitude(buffer: ByteArray, bytesRead: Int): Int {
  // Wrap the byte array in a ByteBuffer and set the order to little-endian
  val shortBuffer =
    ByteBuffer.wrap(buffer, 0, bytesRead).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()

  var maxAmplitude = 0
  // Iterate through the short buffer to find the maximum absolute value
  while (shortBuffer.hasRemaining()) {
    val currentSample = abs(shortBuffer.get().toInt())
    if (currentSample > maxAmplitude) {
      maxAmplitude = currentSample
    }
  }
  return maxAmplitude
}

fun decodeSampledBitmapFromUri(context: Context, uri: Uri, reqWidth: Int, reqHeight: Int): Bitmap? {
  // First, decode with inJustDecodeBounds=true to check dimensions
  val options =
    BitmapFactory.Options().apply {
      inJustDecodeBounds = true
      context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, this)
      }

      // Calculate inSampleSize
      inSampleSize = calculateInSampleSize(this, reqWidth, reqHeight)

      // Decode bitmap with inSampleSize set
      inJustDecodeBounds = false
    }

  return context.contentResolver.openInputStream(uri)?.use {
    BitmapFactory.decodeStream(it, null, options)
  }
}

fun rotateBitmap(bitmap: Bitmap, orientation: Int): Bitmap {
  val matrix = Matrix()
  when (orientation) {
    ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
    ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
    ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1.0f, 1.0f)
    ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1.0f, -1.0f)
    ExifInterface.ORIENTATION_TRANSPOSE -> {
      matrix.postRotate(90f)
      matrix.preScale(-1.0f, 1.0f)
    }
    ExifInterface.ORIENTATION_TRANSVERSE -> {
      matrix.postRotate(270f)
      matrix.preScale(-1.0f, 1.0f)
    }
    ExifInterface.ORIENTATION_NORMAL -> return bitmap
    else -> return bitmap
  }
  return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

private fun calculateInSampleSize(
  options: BitmapFactory.Options,
  reqWidth: Int,
  reqHeight: Int,
): Int {
  // Raw height and width of image
  val height: Int = options.outHeight
  val width: Int = options.outWidth
  var inSampleSize = 1

  if (height > reqHeight || width > reqWidth) {
    // Calculate the ratio of height and width to the requested height and width
    val heightRatio = (height.toFloat() / reqHeight.toFloat()).roundToInt()
    val widthRatio = (width.toFloat() / reqWidth.toFloat()).roundToInt()

    // Choose the largest ratio as inSampleSize value to ensure
    // that both dimensions are smaller than or equal to the requested dimensions.
    inSampleSize = max(heightRatio, widthRatio)
  }

  return inSampleSize
}

/** A simplified representation of a chat message for PDF rendering. */
data class PdfMessage(
  val sender: String,
  val content: String,
//  val timestamp: String,
  val isUser: Boolean
)

/** Converts various ChatMessage types into a simplified PdfMessage for rendering. */
private fun convertToPdfMessage(chatMessage: ChatMessage, modelName: String): PdfMessage? {
  val sender = when (chatMessage.side) {
    ChatSide.USER -> "You"
    ChatSide.AGENT -> modelName
    ChatSide.SYSTEM -> "System"
  }
  val content = when (chatMessage) {
    is ChatMessageText -> chatMessage.content
    is ChatMessageBenchmarkLlmResult -> {
      val stats = chatMessage.orderedStats.joinToString("\n") { stat ->
        val value = chatMessage.statValues[stat.id]
        "${stat.label}: ${"%.2f".format(value)} ${stat.unit}"
      }
      "LLM Benchmark Results:\n$stats"
    }
    else -> {
      Log.w("PDFGenerator", "Unsupported ChatMessage type for PDF: ${chatMessage.type}")
      return null
    }
  }

  return PdfMessage(
    sender = sender,
    content = content,
    isUser = chatMessage.side == ChatSide.USER
  )
}

/** Helper function to split text into lines */
fun splitTextIntoLines(text: String, paint: Paint, maxWidth: Float): List<String> {
  val lines = mutableListOf<String>()
  val words = text.split(" ")
  var currentLine = StringBuilder()

  for (word in words) {
    if (paint.measureText(currentLine.toString() + (if (currentLine.isNotEmpty()) " " else "") + word) <= maxWidth) {
      if (currentLine.isNotEmpty()) {
        currentLine.append(" ")
      }
      currentLine.append(word)
    } else {
      if (currentLine.isNotEmpty()) {
        lines.add(currentLine.toString())
      }
      currentLine = StringBuilder(word)
    }
  }
  if (currentLine.isNotEmpty()) {
    lines.add(currentLine.toString())
  }
  return lines
}

/** Creates a PdfDocument from a list of chat messages, with the specified model name. */
private fun drawBackgroundBlobs(canvas: Canvas) {
  val yellowBlobPaint = Paint().apply {
    color = Color.parseColor("#FFF6B704")
    style = Paint.Style.FILL
  }
  val redBlobPaint = Paint().apply {
    color = Color.parseColor("#FFE54335")
    style = Paint.Style.FILL
  }
  val greenBlobPaint = Paint().apply {
    color = Color.parseColor("#FF34A353")
    style = Paint.Style.FILL
  }
  val blueBlobPaint = Paint().apply {
    color = Color.parseColor("#FF4280EF")
    style = Paint.Style.FILL
  }

  val yellowPath = Path()
  yellowPath.moveTo(37.46f, 2.58f)
  yellowPath.cubicTo(41.92f, -0.86f, 48.12f, -0.86f, 52.58f, 2.58f)
  yellowPath.lineTo(63.78f, 11.22f)
  yellowPath.cubicTo(64.65f, 11.88f, 65.6f, 12.43f, 66.6f, 12.85f)
  yellowPath.lineTo(79.66f, 18.28f)
  yellowPath.cubicTo(84.86f, 20.43f, 87.96f, 25.83f, 87.22f, 31.42f)
  yellowPath.lineTo(85.37f, 45.5f)
  yellowPath.cubicTo(85.23f, 46.58f, 85.23f, 47.68f, 85.37f, 48.76f)
  yellowPath.lineTo(87.22f, 62.83f)
  yellowPath.cubicTo(87.96f, 68.42f, 84.86f, 73.81f, 79.66f, 75.98f)
  yellowPath.lineTo(66.6f, 81.39f)
  yellowPath.cubicTo(65.6f, 81.81f, 64.65f, 82.35f, 63.79f, 83.02f)
  yellowPath.lineTo(52.59f, 91.66f)
  yellowPath.cubicTo(48.12f, 95.1f, 41.92f, 95.1f, 37.46f, 91.66f)
  yellowPath.lineTo(26.26f, 83.02f)
  yellowPath.cubicTo(25.39f, 82.35f, 24.44f, 81.81f, 23.44f, 81.39f)
  yellowPath.lineTo(10.38f, 75.97f)
  yellowPath.cubicTo(-4.82f, 73.8f, -7.92f, 68.41f, -7.18f, 62.82f)
  yellowPath.lineTo(-5.33f, 48.75f)
  yellowPath.cubicTo(-5.19f, 47.67f, -5.19f, 46.57f, -5.33f, 45.49f)
  yellowPath.lineTo(-7.18f, 31.42f)
  yellowPath.cubicTo(-7.92f, 25.82f, -4.81f, 20.43f, 0.39f, 18.28f)
  yellowPath.lineTo(13.45f, 12.85f)
  yellowPath.cubicTo(14.45f, 12.43f, 15.4f, 11.89f, 16.26f, 11.22f)
  yellowPath.lineTo(27.47f, 2.58f)
  yellowPath.close()

  val redPath = Path()
  redPath.moveTo(118.52f, 77.54f)
  redPath.cubicTo(103.68f, 62.69f, 103.68f, 38.81f, 118.52f, 23.96f)
  redPath.lineTo(131.12f, 11.34f)
  redPath.cubicTo(145.97f, -3.51f, 170.02f, -3.51f, 184.87f, 11.34f)
  redPath.cubicTo(199.71f, 26.19f, 199.71f, 50.27f, 184.87f, 65.12f)
  redPath.lineTo(172.26f, 77.74f)
  redPath.cubicTo(157.42f, 92.59f, 133.56f, 92.59f, 118.52f, 77.54f)
  redPath.close()

  val greenPath = Path()
  greenPath.moveTo(90.04f, 153.95f)
  greenPath.cubicTo(90.04f, 129.06f, 69.89f, 108.89f, 45.02f, 108.89f)
  greenPath.cubicTo(20.16f, 108.89f, 0f, 129.06f, 0f, 153.95f)
  greenPath.cubicTo(0f, 178.83f, 20.16f, 199f, 45.02f, 199f)
  greenPath.cubicTo(69.89f, 199f, 90.04f, 178.83f, 90.04f, 153.95f)
  greenPath.close()

  val bluePath = Path()
  bluePath.moveTo(109.25f, 145.13f)
  bluePath.cubicTo(101.39f, 127.01f, 119.77f, 108.61f, 137.89f, 116.49f)
  bluePath.lineTo(140.89f, 117.79f)
  bluePath.cubicTo(146.41f, 120.19f, 152.69f, 120.19f, 158.02f, 117.79f)
  bluePath.lineTo(161.02f, 116.49f)
  bluePath.cubicTo(179.12f, 108.62f, 197.51f, 127.02f, 189.64f, 145.14f)
  bluePath.lineTo(188.34f, 148.14f)
  bluePath.cubicTo(185.94f, 153.68f, 185.94f, 159.96f, 188.34f, 165.49f)
  bluePath.lineTo(189.64f, 168.49f)
  bluePath.cubicTo(197.51f, 186.62f, 179.13f, 205.01f, 161.01f, 197.14f)
  bluePath.lineTo(158.02f, 195.84f)
  bluePath.cubicTo(152.49f, 193.44f, 146.21f, 193.44f, 140.88f, 195.84f)
  bluePath.lineTo(137.88f, 197.14f)
  bluePath.cubicTo(119.78f, 205.01f, 101.39f, 186.61f, 109.26f, 168.49f)
  bluePath.lineTo(110.56f, 165.49f)
  bluePath.cubicTo(112.97f, 159.96f, 112.97f, 153.68f, 110.56f, 148.14f)
  bluePath.lineTo(109.25f, 145.13f)
  bluePath.close()

  val matrix = Matrix()

  matrix.setScale(1.5f, 1.5f)
  matrix.postTranslate(50f, -30f)
  yellowPath.transform(matrix)
  canvas.drawPath(yellowPath, yellowBlobPaint)

  matrix.reset()
  matrix.setScale(1.5f, 1.5f)
  matrix.postTranslate(350f, 0f)
  redPath.transform(matrix)
  canvas.drawPath(redPath, redBlobPaint)

  matrix.reset()
  matrix.setScale(1.5f, 1.5f)
  matrix.postTranslate(0f, 500f)
  greenPath.transform(matrix)
  canvas.drawPath(greenPath, greenBlobPaint)

  matrix.reset()
  matrix.setScale(1.5f, 1.5f)
  matrix.postTranslate(300f, 400f)
  bluePath.transform(matrix)
  canvas.drawPath(bluePath, blueBlobPaint)
}


/** Creates a PdfDocument from a list of chat messages, with the specified model name. */
fun createChatPdfDocument(
  context: Context,
  chatMessages: List<ChatMessage>,
  modelName: String
): PdfDocument {
  val document = PdfDocument()
  val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create()
  var page = document.startPage(pageInfo)
  var canvas = page.canvas

  val textPaint = Paint().apply {
    color = Color.BLACK
    textSize = 20f
  }
  val userBubblePaint = Paint().apply {
    color = Color.parseColor("#E0E0E0")
    style = Paint.Style.FILL
  }
  val aiBubblePaint = Paint().apply {
    color = Color.parseColor("#DCF8C6")
    style = Paint.Style.FILL
  }

  val margin = 50f
  var y = margin
  val pageHeight = pageInfo.pageHeight
  val maxBubbleWidth = 450f

  fun drawPageHeader(canvas: Canvas) {
    canvas.drawColor(Color.WHITE)
    drawBackgroundBlobs(canvas)
    val logoBitmap = BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher_foreground)
    val logoWidth = 50f
    val logoHeight =
      logoWidth * (logoBitmap?.height?.toFloat() ?: 0f) / (logoBitmap?.width?.toFloat() ?: 1f)
    canvas.drawText(
      "AI Edge Gallery Chat",
      margin + 10f,
      margin + logoHeight / 2,
      textPaint.apply { textSize = 40f; isFakeBoldText = true;color = Color.BLACK }
    )
  }

  drawPageHeader(canvas)
  y += 60f

  chatMessages.forEach { message ->
    val pdfMessage = convertToPdfMessage(message, modelName)
    if (pdfMessage != null) {
      val padding = 10f
      val lineSpacing = 8f
      val horizontalPadding = 8f   // much smaller than before
      val verticalPadding = 6f
      val lines = splitTextIntoLines(pdfMessage.content, textPaint, maxBubbleWidth - (2 * horizontalPadding))
      val rect = Rect()
      textPaint.getTextBounds("A", 0, 1, rect)
      val lineHeight = rect.height().toFloat() + lineSpacing

      val bubblePaint = if (pdfMessage.isUser) userBubblePaint else aiBubblePaint

      //  Compute bubble width ONCE for entire message
      val maxLineWidth = lines.maxOfOrNull { textPaint.measureText(it) } ?: 0f
      val bubbleWidth = (maxLineWidth + 2 * horizontalPadding).coerceAtMost(maxBubbleWidth)

      val bubbleLeft =
        if (pdfMessage.isUser) canvas.width - bubbleWidth - margin else margin
      val bubbleRight = bubbleLeft + bubbleWidth

      var startIndex = 0
      var chunkCount = 0
      while (startIndex < lines.size) {
        // how many lines fit in current page
        val availableHeight = pageHeight - margin - y - 60f
        val maxLinesThisPage = (availableHeight / lineHeight).toInt().coerceAtLeast(1)
        val endIndex = (startIndex + maxLinesThisPage).coerceAtMost(lines.size)

        val chunk = lines.subList(startIndex, endIndex)

        val bubbleHeight = verticalPadding + (lines.size * lineHeight) + verticalPadding
        val bubbleTop = y + 20f
        val bubbleBottom = bubbleTop + bubbleHeight

        // check page break
        if (bubbleBottom > pageHeight - margin) {
          document.finishPage(page)
          page = document.startPage(pageInfo)
          canvas = page.canvas
          drawPageHeader(canvas)
          y = margin + 60f
          continue
        }

        //  Sender or "continued"
        if (chunkCount == 0) {
          canvas.drawText(
            pdfMessage.sender,
            if (pdfMessage.isUser)
              canvas.width - margin - textPaint.measureText(pdfMessage.sender)
            else margin,
            y,
            textPaint.apply { textSize = 16f; isFakeBoldText = true;color = Color.BLACK }
          )
        } else {
          canvas.drawText(
            "(continued)",
            bubbleLeft,
            y,
            textPaint.apply { textSize = 14f; isFakeBoldText = false; color = Color.GRAY }
          )
//          textPaint.apply { textSize = 16f; isFakeBoldText = true; color = Color.BLACK}

        }
        y += 20f

        // bubble background (same width across chunks)
        canvas.drawRoundRect(
          bubbleLeft,
          bubbleTop,
          bubbleRight,
          bubbleBottom,
          12f, 12f,  // slightly smaller corners
          bubblePaint
        )

        // draw text
        var textY = bubbleTop + verticalPadding + rect.height()
        val textX = bubbleLeft + horizontalPadding
        for (line in lines) {
          canvas.drawText(line, textX, textY, textPaint)
          textY += lineHeight
        }


        y = bubbleBottom + 20f
        startIndex = endIndex
        chunkCount++
      }
    }
  }


  document.finishPage(page)
  return document
}



fun writePdfToUri(context: Context, pdfDocument: PdfDocument, parcelFileDescriptor: ParcelFileDescriptor) {
  try {
    // Correctly open the output stream from the ParcelFileDescriptor
    val outputStream = FileOutputStream(parcelFileDescriptor.fileDescriptor)

    // Write the PDF content to the output stream
    pdfDocument.writeTo(outputStream)

    // Close the streams and document
    outputStream.close()
    pdfDocument.close()

    // Success Toast
    Toast.makeText(context, "PDF saved to Downloads folder!", Toast.LENGTH_LONG).show()
    Log.d("PDFGenerator", "PDF saved successfully.")
  } catch (e: Exception) {
    // Failure Toast
    Log.e("PDFGenerator", "Failed to save PDF", e)
    Toast.makeText(context, "Failed to save PDF.", Toast.LENGTH_SHORT).show()
  } finally {
    // Ensure the parcel file descriptor is closed in all cases
    try {
      parcelFileDescriptor.close()
    } catch (e: IOException) {
      e.printStackTrace()
    }
  }
}
