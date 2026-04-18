package com.google.ai.edge.gallery.ui.edgeai

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.gallery.ui.theme.appFontFamily

@Composable
fun EdgeModelHub(
  modelManagerViewModel: ModelManagerViewModel,
  onBack: () -> Unit,
  onModelSelected: (Model) -> Unit = {},
) {
  val mmState by modelManagerViewModel.uiState.collectAsState()
  var selectedTab by remember { mutableIntStateOf(0) }

  // Get all LLM models from the LLM_CHAT task
  val llmTask = mmState.tasks.find { it.id == BuiltInTaskId.LLM_CHAT }
  val allModels = llmTask?.models ?: emptyList()

  val downloadedModels = allModels.filter {
    mmState.modelDownloadStatus[it.name]?.status == ModelDownloadStatusType.SUCCEEDED
  }
  val availableModels = allModels.filter {
    mmState.modelDownloadStatus[it.name]?.status != ModelDownloadStatusType.SUCCEEDED
  }

  val usedBytes = downloadedModels.sumOf { it.sizeInBytes }
  val usedGb = usedBytes / 1_000_000_000.0

  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(EdgeBg)
      .windowInsetsPadding(WindowInsets.statusBars)
  ) {
    Column(modifier = Modifier.fillMaxSize()) {

      // Header
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .height(56.dp)
          .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        IconButton(onClick = onBack) {
          Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Back", tint = EdgeTextDim)
        }
        Text(text = "Models", color = EdgeText, fontSize = 18.sp, fontFamily = appFontFamily, fontWeight = FontWeight.Bold)
      }

      // Storage panel
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp)
          .clip(RoundedCornerShape(EdgeRadius))
          .background(EdgeSurface)
          .border(1.dp, EdgeBorderStrong, RoundedCornerShape(EdgeRadius))
          .padding(16.dp),
      ) {
        Column {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(text = "Downloaded", color = EdgeTextDim, fontSize = 12.sp, fontFamily = appFontFamily)
            Text(
              text = "%.1f GB · %d model%s".format(usedGb, downloadedModels.size, if (downloadedModels.size == 1) "" else "s"),
              color = EdgeText,
              fontSize = 13.sp,
              fontFamily = FontFamily.Monospace,
              fontWeight = FontWeight.SemiBold,
            )
          }
          Spacer(Modifier.height(10.dp))
          Box(
            modifier = Modifier.fillMaxWidth().height(6.dp)
              .clip(RoundedCornerShape(3.dp)).background(EdgeSurface2),
          ) {
            if (allModels.isNotEmpty()) {
              Box(
                modifier = Modifier
                  .fillMaxWidth(fraction = (downloadedModels.size.toFloat() / allModels.size).coerceIn(0f, 1f))
                  .height(6.dp).background(EdgeAccent),
              )
            }
          }
        }
      }

      Spacer(Modifier.height(16.dp))

      // Tabs
      TabRow(
        selectedTabIndex = selectedTab,
        containerColor = EdgeBg,
        contentColor = EdgeAccent,
        indicator = { tabPositions ->
          TabRowDefaults.SecondaryIndicator(
            modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
            color = EdgeAccent, height = 2.dp,
          )
        },
        divider = { Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(EdgeBorderStrong)) },
      ) {
        Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 },
          text = { Text(text = "Installed · ${downloadedModels.size}", color = if (selectedTab == 0) EdgeAccent else EdgeTextDim, fontSize = 13.sp, fontFamily = appFontFamily) })
        Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 },
          text = { Text(text = "Catalog · ${allModels.size}", color = if (selectedTab == 1) EdgeAccent else EdgeTextDim, fontSize = 13.sp, fontFamily = appFontFamily) })
      }

      val displayModels = if (selectedTab == 0) downloadedModels else availableModels

      if (displayModels.isEmpty() && !mmState.loadingModelAllowlist) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          Text(
            text = if (selectedTab == 0) "No models downloaded yet" else "All models downloaded",
            color = EdgeTextMute, fontSize = 14.sp, fontFamily = appFontFamily,
          )
        }
      } else {
        LazyColumn(
          modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
          contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          items(displayModels, key = { it.name }) { model ->
            RealModelRow(
              model = model,
              isActive = mmState.selectedModel.name == model.name,
              downloadStatus = mmState.modelDownloadStatus[model.name],
              isInstalled = selectedTab == 0,
              onSelect = { onModelSelected(model) },
              onDownload = {
                if (llmTask != null) modelManagerViewModel.downloadModel(llmTask, model)
              },
              onCancel = { modelManagerViewModel.cancelDownloadModel(model) },
              onDelete = { modelManagerViewModel.deleteModel(model) },
            )
          }
        }
      }
    }
  }
}

@Composable
private fun RealModelRow(
  model: Model,
  isActive: Boolean,
  downloadStatus: com.google.ai.edge.gallery.data.ModelDownloadStatus?,
  isInstalled: Boolean,
  onSelect: () -> Unit,
  onDownload: () -> Unit,
  onCancel: () -> Unit,
  onDelete: () -> Unit,
) {
  val displayName = model.displayName.ifEmpty { model.name }
  val abbrev = displayName.take(3).uppercase()
  val sizeGb = if (model.sizeInBytes > 0) "%.1f GB".format(model.sizeInBytes / 1_000_000_000.0) else "?"
  val isDownloading = downloadStatus?.status == ModelDownloadStatusType.IN_PROGRESS
  val downloadProgress = if (isDownloading && (downloadStatus?.totalBytes ?: 0L) > 0L)
    downloadStatus!!.receivedBytes.toFloat() / downloadStatus.totalBytes else 0f
  var showDeleteConfirm by remember { mutableStateOf(false) }

  if (showDeleteConfirm) {
    AlertDialog(
      onDismissRequest = { showDeleteConfirm = false },
      containerColor = EdgeSurface,
      titleContentColor = EdgeText,
      textContentColor = EdgeTextDim,
      title = { Text(text = "Delete $displayName?", fontFamily = appFontFamily, fontWeight = FontWeight.Bold) },
      text = {
        Text(
          text = if (isActive)
            "This is your active model. Deleting it will require downloading again before you can use it."
          else
            "This will permanently remove the model files (${sizeGb}) from your device.",
          fontFamily = appFontFamily,
          fontSize = 14.sp,
        )
      },
      confirmButton = {
        Box(
          modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF3D1A1A))
            .border(1.dp, Color(0xFF7A2020), RoundedCornerShape(8.dp))
            .clickable { showDeleteConfirm = false; onDelete() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        ) { Text(text = "Delete", color = Color(0xFFFF5555), fontSize = 13.sp, fontFamily = appFontFamily, fontWeight = FontWeight.SemiBold) }
      },
      dismissButton = {
        Box(
          modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(EdgeSurface2)
            .border(1.dp, EdgeBorderStrong, RoundedCornerShape(8.dp))
            .clickable { showDeleteConfirm = false }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        ) { Text(text = "Cancel", color = EdgeTextDim, fontSize = 13.sp, fontFamily = appFontFamily) }
      },
    )
  }

  Box(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(EdgeRadius))
      .background(if (isActive) EdgeAccentSoft else EdgeSurface)
      .border(width = if (isActive) 1.5.dp else 1.dp, color = if (isActive) EdgeAccent else EdgeBorderStrong, shape = RoundedCornerShape(EdgeRadius))
      .clickable(enabled = isInstalled, onClick = onSelect)
      .padding(16.dp),
  ) {
    Column {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
          modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp))
            .background(EdgeSurface2).border(1.dp, EdgeBorderStrong, RoundedCornerShape(10.dp)),
          contentAlignment = Alignment.Center,
        ) {
          Text(text = abbrev, color = EdgeAccent, fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = displayName, color = EdgeText, fontSize = 15.sp, fontFamily = appFontFamily, fontWeight = FontWeight.SemiBold)
            if (isActive) {
              Box(
                modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(EdgeAccentSoft)
                  .border(1.dp, EdgeAccentBorder, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
              ) { Text(text = "Active", color = EdgeAccent, fontSize = 9.sp, fontFamily = FontFamily.Monospace) }
            }
          }
          Spacer(Modifier.height(4.dp))
          Text(text = sizeGb, color = EdgeTextMute, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
        Spacer(Modifier.width(8.dp))
        when {
          isDownloading -> {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Text(
                text = "${(downloadProgress * 100).toInt()}%",
                color = EdgeAccent, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
              )
              Box(
                modifier = Modifier
                  .clip(RoundedCornerShape(8.dp))
                  .background(EdgeSurface2)
                  .border(1.dp, EdgeBorderStrong, RoundedCornerShape(8.dp))
                  .clickable(onClick = onCancel)
                  .padding(horizontal = 10.dp, vertical = 6.dp),
              ) {
                Text(text = "Cancel", color = EdgeTextDim, fontSize = 11.sp, fontFamily = appFontFamily)
              }
            }
          }
          !isInstalled -> {
            Box(
              modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(EdgeAccent)
                .clickable(onClick = onDownload).padding(horizontal = 12.dp, vertical = 6.dp),
            ) { Text(text = "Download", color = Color.Black, fontSize = 12.sp, fontFamily = appFontFamily, fontWeight = FontWeight.Bold) }
          }
          isInstalled -> {
            Box(
              modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(EdgeSurface2)
                .border(1.dp, EdgeBorderStrong, RoundedCornerShape(8.dp))
                .clickable { showDeleteConfirm = true }
                .padding(horizontal = 12.dp, vertical = 6.dp),
            ) { Text(text = "Delete", color = EdgeTextDim, fontSize = 12.sp, fontFamily = appFontFamily) }
          }
        }
      }
      if (isDownloading) {
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(
          progress = { downloadProgress },
          modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
          color = EdgeAccent,
          trackColor = EdgeSurface2,
        )
      }
    }
  }
}
