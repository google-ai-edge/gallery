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

package com.google.ai.edge.gallery.ui.common

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import android.util.Size
import androidx.pdf.PdfDocument
import androidx.pdf.PdfFeature
import androidx.pdf.SandboxedPdfLoader
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "AGSandboxedPdf"

/**
 * Canonical entry point for reading PDF files in the app.
 *
 * PDFs are parsed by androidx.pdf's [SandboxedPdfLoader], which runs the platform PDF renderer in
 * an isolated process (see go/android-security-violations/PDF_RENDERER). Never use
 * `android.graphics.pdf.PdfRenderer` directly, including via reflection.
 *
 * Opening and rendering pages ([renderPage]) work on every supported API level. Text extraction
 * requires Android 15+, or Android 12-14 with SDK extension 13+; [extractText] returns null on
 * other devices.
 */
object SandboxedPdf {
  /** The default maximum number of pages to extract text from. */
  const val DEFAULT_MAX_TEXT_PAGES = 10

  /** The maximum time to wait for the sandbox to open a document. */
  private const val OPEN_TIMEOUT_MS = 30_000L

  /**
   * Opens the PDF [file] in the sandbox, or returns null if it cannot be opened within
   * [OPEN_TIMEOUT_MS]. The caller owns the returned [PdfDocument] and must close it.
   */
  suspend fun open(context: Context, file: File): PdfDocument? {
    var fileDescriptor: ParcelFileDescriptor? = null
    return try {
      val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
      fileDescriptor = fd
      // On success, the returned document takes ownership of the file descriptor. The timeout
      // guards against the sandbox service never connecting (e.g. if its process crashes on
      // startup), which would otherwise suspend forever.
      val document =
        withTimeoutOrNull(OPEN_TIMEOUT_MS) {
          SandboxedPdfLoader(context).openDocument(Uri.fromFile(file), fd)
        }
      if (document == null) {
        Log.e(TAG, "Timed out opening PDF in the sandbox: ${file.absolutePath}")
        fd.close()
      }
      document
    } catch (e: CancellationException) {
      fileDescriptor?.close()
      throw e
    } catch (e: Exception) {
      Log.e(TAG, "Failed to open PDF: ${file.absolutePath}", e)
      fileDescriptor?.close()
      null
    }
  }

  /**
   * Renders the page at [pageIndex] of [document] scaled to [widthPx] pixels wide, preserving its
   * aspect ratio. Rendering happens in the sandbox. Returns null if the page cannot be rendered.
   */
  suspend fun renderPage(document: PdfDocument, pageIndex: Int, widthPx: Int): Bitmap? {
    if (pageIndex !in 0 until document.pageCount || widthPx <= 0) {
      return null
    }
    return try {
      val pageInfo = document.getPageInfo(pageIndex)
      if (pageInfo.width <= 0 || pageInfo.height <= 0) {
        return null
      }
      val heightPx = (pageInfo.height.toFloat() * widthPx / pageInfo.width).roundToInt()
      document.getPageBitmapSource(pageIndex).use { source ->
        source.getBitmap(Size(widthPx, heightPx.coerceAtLeast(1)))
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.e(TAG, "Failed to render page $pageIndex of PDF: ${document.uri}", e)
      null
    }
  }

  /**
   * Extracts the text of up to [maxPages] pages of the PDF [file], with each page prefixed by a
   * `--- Page N ---` header. Returns null if the file cannot be opened, text extraction is not
   * supported on this device, or the PDF contains no text.
   */
  suspend fun extractText(
    context: Context,
    file: File,
    maxPages: Int = DEFAULT_MAX_TEXT_PAGES,
  ): String? = open(context, file)?.use { document -> extractText(document, maxPages) }

  /** Same as [extractText] above, for an already opened [document]. */
  suspend fun extractText(document: PdfDocument, maxPages: Int = DEFAULT_MAX_TEXT_PAGES): String? {
    if (!document.isFeatureSupported(PdfFeature.TEXT_EXTRACTION)) {
      Log.w(
        TAG,
        "PDF text extraction requires Android 15+ or Android 12-14 with SDK extension 13+.",
      )
      return null
    }
    return try {
      buildString {
          for (pageIndex in 0 until minOf(document.pageCount, maxPages)) {
            val pageContent =
              document
                .getPageContent(pageIndex)
                ?.textContents
                ?.map { it.text.trim() }
                ?.filter { it.isNotEmpty() }
                ?.joinToString("\n")
            if (!pageContent.isNullOrEmpty()) {
              append("--- Page ${pageIndex + 1} ---\n")
              append(pageContent).append("\n\n")
            }
          }
        }
        .trim()
        .ifEmpty { null }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.e(TAG, "Failed to extract text from PDF: ${document.uri}", e)
      null
    }
  }
}
