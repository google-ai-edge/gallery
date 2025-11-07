/*
 * Copyright 2025 Neural Forge
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

package com.neuralforge.mobile.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neuralforge.mobile.downloader.ModelDownloadManager.DownloadProgress
import com.neuralforge.mobile.downloader.ModelDownloadManager.DownloadState
import kotlin.math.roundToInt

/**
 * Enhanced download progress component with animations
 */
@Composable
fun EnhancedDownloadProgress(
    downloadState: DownloadState,
    modelName: String,
    modifier: Modifier = Modifier,
    onCancel: (() -> Unit)? = null
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DownloadStateIcon(downloadState)
                    Text(
                        text = modelName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (onCancel != null && downloadState is DownloadState.Downloading) {
                    IconButton(onClick = onCancel) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Cancel download"
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Progress content
            when (downloadState) {
                is DownloadState.Preparing -> {
                    PreparingIndicator()
                }
                is DownloadState.Downloading -> {
                    DownloadingProgress(downloadState.progress)
                }
                is DownloadState.Merging -> {
                    MergingIndicator()
                }
                is DownloadState.Completed -> {
                    CompletedIndicator()
                }
                is DownloadState.Failed -> {
                    FailedIndicator(downloadState.error.message ?: "Unknown error")
                }
                is DownloadState.DownloadingPart -> {
                    PartDownloadIndicator(downloadState.current, downloadState.total)
                }
            }
        }
    }
}

@Composable
private fun DownloadStateIcon(state: DownloadState) {
    val icon = when (state) {
        is DownloadState.Preparing -> Icons.Default.HourglassEmpty
        is DownloadState.Downloading -> Icons.Default.CloudDownload
        is DownloadState.Merging -> Icons.Default.MergeType
        is DownloadState.Completed -> Icons.Default.CheckCircle
        is DownloadState.Failed -> Icons.Default.Error
        is DownloadState.DownloadingPart -> Icons.Default.CloudDownload
    }

    val tint = when (state) {
        is DownloadState.Completed -> Color(0xFF4CAF50) // Green
        is DownloadState.Failed -> Color(0xFFF44336) // Red
        else -> MaterialTheme.colorScheme.primary
    }

    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = tint,
        modifier = Modifier.size(24.dp)
    )
}

@Composable
private fun PreparingIndicator() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(24.dp),
            strokeWidth = 3.dp
        )
        Text(
            text = "Preparing download...",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DownloadingProgress(progress: DownloadProgress) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Animated progress bar
        AnimatedProgressBar(
            progress = progress.progressPercent / 100f,
            modifier = Modifier.fillMaxWidth()
        )

        // Stats row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = "${progress.progressPercent.roundToInt()}%",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "${formatBytes(progress.bytesDownloaded)} / ${formatBytes(progress.totalBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "${String.format("%.2f", progress.downloadRate)} MB/s",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "ETA: ${formatTime(progress.estimatedTimeRemaining)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Chunk progress if multi-part
        if (progress.totalChunks > 1) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    Icons.Default.Splitscreen,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Chunk ${progress.currentChunk} of ${progress.totalChunks}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun AnimatedProgressBar(
    progress: Float,
    modifier: Modifier = Modifier
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
        label = "progress"
    )

    // Setup shimmer animation outside of Canvas
    val infiniteTransition = rememberInfiniteTransition(label = "shimmer")
    val shimmerOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerOffset"
    )

    Box(
        modifier = modifier
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
    ) {
        // Background
        LinearProgressIndicator(
            progress = { animatedProgress },
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )

        // Shimmer effect
        Canvas(modifier = Modifier.fillMaxSize()) {
            val shimmerWidth = size.width * 0.3f
            val shimmerPosition = (shimmerOffset * (size.width + shimmerWidth)) - shimmerWidth

            // Only show shimmer on progress portion
            val progressWidth = size.width * animatedProgress
            if (shimmerPosition < progressWidth) {
                drawRect(
                    color = Color.White.copy(alpha = 0.2f),
                    topLeft = Offset(shimmerPosition, 0f),
                    size = Size(shimmerWidth, size.height)
                )
            }
        }
    }
}

@Composable
private fun MergingIndicator() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(24.dp),
            strokeWidth = 3.dp
        )
        Text(
            text = "Merging model parts...",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun CompletedIndicator() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
    ) {
        Icon(
            Icons.Default.CheckCircle,
            contentDescription = null,
            tint = Color(0xFF4CAF50),
            modifier = Modifier.size(32.dp)
        )
        Text(
            text = "Download completed!",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF4CAF50)
        )
    }
}

@Composable
private fun FailedIndicator(errorMessage: String) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                Icons.Default.Error,
                contentDescription = null,
                tint = Color(0xFFF44336),
                modifier = Modifier.size(32.dp)
            )
            Text(
                text = "Download failed",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFF44336)
            )
        }
        Text(
            text = errorMessage,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PartDownloadIndicator(current: Int, total: Int) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        LinearProgressIndicator(
            progress = { current.toFloat() / total },
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = "Downloading part $current of $total",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// Helper functions
private fun formatBytes(bytes: Long): String {
    return when {
        bytes >= 1_000_000_000 -> String.format("%.2f GB", bytes / 1_000_000_000.0)
        bytes >= 1_000_000 -> String.format("%.2f MB", bytes / 1_000_000.0)
        bytes >= 1_000 -> String.format("%.2f KB", bytes / 1_000.0)
        else -> "$bytes B"
    }
}

private fun formatTime(seconds: Long): String {
    return when {
        seconds >= 3600 -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
        seconds >= 60 -> "${seconds / 60}m ${seconds % 60}s"
        else -> "${seconds}s"
    }
}
