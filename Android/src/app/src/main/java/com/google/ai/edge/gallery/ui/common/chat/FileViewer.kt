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

package com.google.ai.edge.gallery.ui.common.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.pdf.PdfDocument
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.ui.common.SandboxedPdf
import java.io.File
import kotlinx.coroutines.awaitCancellation

/** Approximate aspect ratio (width / height) of an A4 page, used for page placeholders. */
private const val PLACEHOLDER_PAGE_ASPECT_RATIO = 1f / 1.414f

/** Horizontal padding around rendered PDF pages. */
private val PDF_PAGE_HORIZONTAL_PADDING = 8.dp

/**
 * A full-screen viewer that shows the content of an attached file, similar to the image viewer for
 * attached images.
 *
 * PDF files with an available local copy are rendered page by page (in the androidx.pdf sandbox),
 * with a toggle to switch to the extracted text. All other files (or PDFs without a local copy)
 * show their text content.
 */
@Composable
fun FileViewer(file: ChatMessageFile, onDismissRequest: () -> Unit) {
  val context = LocalContext.current
  val clipboard =
    remember(context) { context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }
  val pdfFile =
    remember(file) {
      file.filePath?.takeIf { file.isPdf }?.let { path -> File(path) }?.takeIf { it.exists() }
    }
  var showExtractedText by remember(file) { mutableStateOf(false) }
  // PDFs whose text could not be extracted are attached with empty content.
  val hasText = file.content.isNotBlank()

  Dialog(
    onDismissRequest = onDismissRequest,
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    Column(
      modifier =
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).safeDrawingPadding()
    ) {
      // Top bar: back button, file name and size, PDF/text toggle, copy button.
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
      ) {
        IconButton(onClick = onDismissRequest) {
          Icon(
            Icons.AutoMirrored.Rounded.ArrowBack,
            contentDescription = stringResource(R.string.close),
            tint = MaterialTheme.colorScheme.onSurface,
          )
        }
        Column(modifier = Modifier.weight(1f).padding(horizontal = 4.dp)) {
          Text(
            text = file.name,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          if (file.sizeString.isNotEmpty()) {
            Text(
              text = file.sizeString,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.outline,
            )
          }
        }
        if (pdfFile != null && hasText) {
          IconButton(onClick = { showExtractedText = !showExtractedText }) {
            if (showExtractedText) {
              Icon(
                Icons.Rounded.PictureAsPdf,
                contentDescription = stringResource(R.string.cd_file_viewer_show_pages),
                tint = MaterialTheme.colorScheme.onSurface,
              )
            } else {
              Icon(
                Icons.AutoMirrored.Rounded.Article,
                contentDescription = stringResource(R.string.cd_file_viewer_show_text),
                tint = MaterialTheme.colorScheme.onSurface,
              )
            }
          }
        }
        if (hasText) {
          IconButton(
            onClick = { clipboard.setPrimaryClip(ClipData.newPlainText(file.name, file.content)) }
          ) {
            Icon(
              Icons.Rounded.ContentCopy,
              contentDescription = stringResource(R.string.copy),
              tint = MaterialTheme.colorScheme.onSurface,
            )
          }
        }
      }
      HorizontalDivider()

      // File content.
      val contentModifier = Modifier.fillMaxWidth().weight(1f)
      val text = if (hasText) file.content else stringResource(R.string.file_viewer_no_text)
      if (pdfFile != null && !showExtractedText) {
        PdfPagesContent(file = pdfFile, fallbackText = text, modifier = contentModifier)
      } else {
        TextContent(text = text, modifier = contentModifier)
      }
    }
  }
}

/** Scrollable, selectable plain text content of a file. */
@Composable
private fun TextContent(text: String, modifier: Modifier = Modifier) {
  SelectionContainer(
    modifier =
      modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp)
  ) {
    Text(
      text = text,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurface,
    )
  }
}

private sealed interface PdfLoadState {
  data object Loading : PdfLoadState

  data object Failed : PdfLoadState

  class Ready(val document: PdfDocument) : PdfLoadState
}

/**
 * Shows the pages of the PDF [file] in a vertically scrolling list. Pages are parsed and rendered
 * by [SandboxedPdf] in an isolated process. Falls back to showing [fallbackText] if the PDF cannot
 * be opened.
 */
@Composable
private fun PdfPagesContent(file: File, fallbackText: String, modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val loadState by
    produceState<PdfLoadState>(initialValue = PdfLoadState.Loading, file) {
      // Keep opening cancellable, and close the document once this leaves the composition. If
      // opening is cancelled midway, SandboxedPdf.open releases what it has opened itself.
      var document: PdfDocument? = null
      try {
        document = SandboxedPdf.open(context, file)
        value = if (document != null) PdfLoadState.Ready(document) else PdfLoadState.Failed
        awaitCancellation()
      } finally {
        document?.close()
      }
    }

  when (val state = loadState) {
    PdfLoadState.Loading ->
      Box(modifier = modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    PdfLoadState.Failed -> TextContent(text = fallbackText, modifier = modifier)
    is PdfLoadState.Ready ->
      BoxWithConstraints(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest)
      ) {
        val pageWidthPx =
          with(LocalDensity.current) { (maxWidth - PDF_PAGE_HORIZONTAL_PADDING * 2).roundToPx() }
            .coerceAtLeast(1)
        val pageCount = state.document.pageCount
        LazyColumn(
          modifier = Modifier.fillMaxSize(),
          contentPadding = PaddingValues(horizontal = PDF_PAGE_HORIZONTAL_PADDING, vertical = 8.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          items(count = pageCount) { index ->
            PdfPage(
              document = state.document,
              index = index,
              pageCount = pageCount,
              widthPx = pageWidthPx,
            )
          }
        }
      }
  }
}

/** A single rendered PDF page. Shows a placeholder while the page is being rendered. */
@Composable
private fun PdfPage(document: PdfDocument, index: Int, pageCount: Int, widthPx: Int) {
  val bitmap by
    produceState<Bitmap?>(initialValue = null, document, index, widthPx) {
      value = SandboxedPdf.renderPage(document, index, widthPx)
    }
  val contentDescription = stringResource(R.string.cd_pdf_page, index + 1, pageCount)
  val curBitmap = bitmap
  if (curBitmap != null) {
    Image(
      bitmap = curBitmap.asImageBitmap(),
      contentDescription = contentDescription,
      contentScale = ContentScale.FillWidth,
      modifier = Modifier.fillMaxWidth().aspectRatio(curBitmap.width.toFloat() / curBitmap.height),
    )
  } else {
    Box(
      modifier =
        Modifier.fillMaxWidth()
          .aspectRatio(PLACEHOLDER_PAGE_ASPECT_RATIO)
          .background(MaterialTheme.colorScheme.surface)
    )
  }
}
