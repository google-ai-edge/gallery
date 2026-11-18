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
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import android.util.LruCache
import android.util.Size
import androidx.pdf.PdfDocument
import androidx.pdf.PdfFeature
import androidx.pdf.SandboxedPdfLoader
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "AGSandboxedPdf"

/**
 * A rectangle to highlight on a rendered PDF page, in fractions of the page size from its top-left
 * corner (`0f` is the left or top edge of the page, `1f` the right or bottom edge).
 */
data class PdfHighlightRect(val left: Float, val top: Float, val right: Float, val bottom: Float)

/**
 * Canonical entry point for reading PDF files in the app.
 *
 * PDFs are parsed by androidx.pdf's [SandboxedPdfLoader], which runs the platform PDF renderer in
 * an isolated process (see go/android-security-violations/PDF_RENDERER). Never use
 * `android.graphics.pdf.PdfRenderer` directly, including via reflection. To show the same pages
 * repeatedly, use [SandboxedPdfPageRenderer], which keeps documents open and caches pages.
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

/**
 * Renders pages of local PDF files with [SandboxedPdf], for screens that show the same pages more
 * than once (for example cited pages that are shown in several places).
 *
 * Each PDF is opened in the sandbox once and kept open until [close] is called, and rendered pages
 * are kept in an LRU cache bounded by bytes. Callers own the instance and must call [close] when
 * they no longer need it (for example when the screen's model is cleaned up).
 *
 * @param ioDispatcher The dispatcher that documents are opened and pages rendered on, usually the
 *   injected `@IoDispatcher`.
 * @param cacheMaxBytes Upper bound on the memory used by cached pages.
 */
class SandboxedPdfPageRenderer(
  private val ioDispatcher: CoroutineDispatcher,
  cacheMaxBytes: Int = DEFAULT_CACHE_MAX_BYTES,
) {
  private val documentsMutex = Mutex()

  /** Open documents by absolute file path. Guarded by [documentsMutex]. */
  private val documents = mutableMapOf<String, PdfDocument>()

  /**
   * Rendered pages by [cacheKey]. [LruCache] is thread-safe; it is only written while holding
   * [documentsMutex], so that [close] leaves it empty.
   */
  private val pageCache =
    object : LruCache<String, Bitmap>(cacheMaxBytes) {
      override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

  /**
   * Returns the page that [renderPage] rendered earlier for the same arguments, or null if it is
   * not cached.
   */
  fun getCachedPage(
    file: File,
    pageIndex: Int,
    widthPx: Int,
    highlights: List<PdfHighlightRect> = emptyList(),
  ): Bitmap? = pageCache.get(cacheKey(file, pageIndex, widthPx, highlights))

  /**
   * Renders the page at [pageIndex] of the PDF [file] scaled to [widthPx] pixels wide. If
   * [highlights] isn't empty, the page is drawn on a white background with the area covered by the
   * [highlights] rectangles tinted yellow. Returns null if the file can't be opened or the page
   * can't be rendered.
   */
  suspend fun renderPage(
    context: Context,
    file: File,
    pageIndex: Int,
    widthPx: Int,
    highlights: List<PdfHighlightRect> = emptyList(),
  ): Bitmap? {
    val key = cacheKey(file, pageIndex, widthPx, highlights)
    pageCache.get(key)?.let {
      return it
    }
    return withContext(ioDispatcher) {
      // Render and cache while holding the lock, so that close() can't close the document
      // mid-render or clear the cache before this page is added.
      documentsMutex.withLock {
        val document = getOrOpenDocumentLocked(context, file) ?: return@withLock null
        val page = SandboxedPdf.renderPage(document, pageIndex, widthPx) ?: return@withLock null
        val rendered = if (highlights.isNotEmpty()) drawHighlights(page, highlights) else page
        pageCache.put(key, rendered)
        rendered
      }
    }
  }

  /** Closes all open PDF documents and drops all cached pages. */
  suspend fun close() {
    // Non-cancellable so that a cancelled cleanup never leaks an open document.
    withContext(NonCancellable) {
      documentsMutex.withLock {
        for (document in documents.values) {
          try {
            document.close()
          } catch (e: Exception) {
            Log.w(TAG, "Failed to close PDF: ${document.uri}", e)
          }
        }
        documents.clear()
        pageCache.evictAll()
      }
    }
  }

  /** Returns the open document for [file], opening it if needed. Must hold [documentsMutex]. */
  private suspend fun getOrOpenDocumentLocked(context: Context, file: File): PdfDocument? {
    documents[file.absolutePath]?.let {
      return it
    }
    // Non-cancellable so that a successfully opened document is always recorded and later closed.
    val document =
      withContext(NonCancellable) { SandboxedPdf.open(context.applicationContext, file) }
        ?: return null
    documents[file.absolutePath] = document
    return document
  }

  /** Returns the [pageCache] key of a page rendered with these arguments. */
  internal fun cacheKey(
    file: File,
    pageIndex: Int,
    widthPx: Int,
    highlights: List<PdfHighlightRect>,
  ): String =
    "${file.absolutePath}:$pageIndex:$widthPx:" +
      highlights.joinToString(separator = ";") { "${it.left},${it.top},${it.right},${it.bottom}" }

  /**
   * Returns a copy of [page] on a white background with the area covered by [highlights] tinted
   * yellow.
   */
  private fun drawHighlights(page: Bitmap, highlights: List<PdfHighlightRect>): Bitmap {
    val width = page.width
    val height = page.height
    // Draw onto a new bitmap: the sandbox may return an immutable or transparent bitmap.
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(Color.WHITE)
    canvas.drawBitmap(page, 0f, 0f, null)
    val highlightPaint =
      Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = HIGHLIGHT_COLOR
        xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
      }
    // One path, so that where the rectangles of adjacent lines overlap the page is tinted once
    // rather than darkened by multiplying twice.
    val highlightPath = Path()
    for (highlight in highlights) {
      val left = highlight.left.coerceIn(0f, 1f)
      val top = highlight.top.coerceIn(0f, 1f)
      val right = highlight.right.coerceIn(left, 1f)
      val bottom = highlight.bottom.coerceIn(top, 1f)
      highlightPath.addRoundRect(
        RectF(width * left, height * top, width * right, height * bottom),
        HIGHLIGHT_CORNER_RADIUS_PX,
        HIGHLIGHT_CORNER_RADIUS_PX,
        Path.Direction.CW,
      )
    }
    canvas.drawPath(highlightPath, highlightPaint)
    return bitmap
  }

  companion object {
    /** Default upper bound on the memory used by cached pages. */
    const val DEFAULT_CACHE_MAX_BYTES = 32 * 1024 * 1024

    private val HIGHLIGHT_COLOR = Color.rgb(255, 242, 140)
    private const val HIGHLIGHT_CORNER_RADIUS_PX = 8f
  }
}
