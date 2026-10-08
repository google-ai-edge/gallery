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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupPositionProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.agent.sessions.SummarizationContextCompactor
import com.google.ai.edge.gallery.common.metrics.InferenceMetrics
import com.google.ai.edge.gallery.common.metrics.InferenceStatus
import com.google.ai.edge.gallery.ui.theme.customColors
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Resolved presentation state for [LiveMetricsBar]. */
internal data class LiveMetricsUiState(
  val speedValue: String,
  val speedAnnotation: String?,
  val speedSubtextLines: List<String>,
  val tokensValue: String,
  val tokensSubtext: String?,
  val ramValue: String,
  val ramSubtext: String?,
  val contextUsageText: String,
  val contextUsedPercent: Float,
  val isHighContextUsage: Boolean,
  val isCompactingContext: Boolean,
)

/**
 * Observes [liveMetricsFlow] and renders [LiveMetricsBar] when metrics for [modelName] are
 * available, isolating high-frequency live telemetry recompositions strictly to this banner.
 */
@Composable
fun LiveMetricsBar(
  liveMetricsFlow: StateFlow<InferenceMetrics?>,
  modelName: String,
  modifier: Modifier = Modifier,
  isCompactingContext: Boolean = false,
) {
  val liveMetrics by liveMetricsFlow.collectAsStateWithLifecycle()
  val activeMetrics =
    liveMetrics?.takeIf { it.metadata.modelName.isEmpty() || it.metadata.modelName == modelName }
      ?: return
  LiveMetricsBar(
    metrics = activeMetrics,
    modifier = modifier,
    isCompactingContext = isCompactingContext,
  )
}

/**
 * Compact banner card above the chat message input bar displaying real-time inference telemetry:
 * token generation speed (prefill/decode throughput, TTFT, and model initialization duration),
 * prompt and output token counts, peak resident memory (PSS MB/GB and device RAM percentage), and
 * context window token utilization with an auto-compaction threshold marker.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveMetricsBar(
  metrics: InferenceMetrics,
  modifier: Modifier = Modifier,
  isCompactingContext: Boolean = false,
) {
  val uiState = resolveLiveMetricsUiState(metrics, isCompactingContext = isCompactingContext)
  val compressThresholdFraction = SummarizationContextCompactor.TOKEN_LIMIT_THRESHOLD_RATIO
  val compressThresholdPercent = (compressThresholdFraction * 100f).toInt()
  val tooltipState = rememberTooltipState(isPersistent = true)
  val coroutineScope = rememberCoroutineScope()

  Surface(
    modifier = modifier.clip(RoundedCornerShape(12.dp)),
    shape = RoundedCornerShape(12.dp),
    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.95f),
    tonalElevation = 1.dp,
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      // Row 1: Speed, Tokens, RAM (3 balanced columns) + dedicated trailing info button
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
      ) {
        MetricColumn(
          label = stringResource(R.string.live_metrics_speed),
          value = uiState.speedValue,
          valueAnnotation = uiState.speedAnnotation,
          subtextLines = uiState.speedSubtextLines,
          modifier = Modifier.weight(1f),
        )
        MetricColumn(
          label = stringResource(R.string.live_metrics_tokens),
          value = uiState.tokensValue,
          subtextLines = listOfNotNull(uiState.tokensSubtext),
          modifier = Modifier.weight(1f),
        )
        MetricColumn(
          label = stringResource(R.string.live_metrics_ram),
          value = uiState.ramValue,
          subtextLines = listOfNotNull(uiState.ramSubtext),
          modifier = Modifier.weight(1f),
        )
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
          TooltipBox(
            positionProvider = rememberClampedTooltipPositionProvider(),
            tooltip = {
              PlainTooltip(modifier = Modifier.widthIn(max = 260.dp)) {
                Text(
                  text =
                    stringResource(R.string.live_metrics_info_tooltip, compressThresholdPercent),
                  style = MaterialTheme.typography.bodySmall,
                )
              }
            },
            state = tooltipState,
          ) {
            IconButton(
              onClick = { coroutineScope.launch { tooltipState.show() } },
              modifier = Modifier.size(20.dp),
            ) {
              Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = stringResource(R.string.cd_live_metrics_info),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                modifier = Modifier.size(14.dp),
              )
            }
          }
        }
      }

      // Row 2: Context window usage + progress bar + auto-compaction threshold marker
      val warningColor = MaterialTheme.customColors.warningTextColor
      Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = stringResource(R.string.live_metrics_context),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Text(
            text = uiState.contextUsageText,
            style =
              MaterialTheme.typography.labelSmall.copy(
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                fontFeatureSettings = TABULAR_NUMS_FEATURE,
              ),
            color =
              when {
                uiState.isCompactingContext -> MaterialTheme.colorScheme.primary
                uiState.isHighContextUsage -> warningColor
                else -> MaterialTheme.colorScheme.onSurfaceVariant
              },
          )
        }

        val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
        BoxWithConstraints(
          modifier = Modifier.fillMaxWidth().height(7.dp),
          contentAlignment = AbsoluteAlignment.CenterLeft,
        ) {
          if (uiState.isCompactingContext) {
            LinearProgressIndicator(
              modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(1.5.dp)),
              color = MaterialTheme.colorScheme.primary,
              trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
          } else {
            LinearProgressIndicator(
              progress = { (uiState.contextUsedPercent / 100f).coerceIn(0f, 1f) },
              modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(1.5.dp)),
              color =
                if (uiState.isHighContextUsage) {
                  warningColor
                } else {
                  MaterialTheme.colorScheme.primary
                },
              trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )

            // Automatic context compression threshold tick mark. Use physical left alignment and
            // absoluteOffset so the tick mirrors to 25% from the left in RTL layouts, matching
            // the RTL progress fill and Row weights below.
            val tickFractionFromLeft =
              if (isRtl) 1f - compressThresholdFraction else compressThresholdFraction
            Box(
              modifier =
                Modifier.absoluteOffset(x = maxWidth * tickFractionFromLeft - 0.75.dp)
                  .width(1.5.dp)
                  .height(7.dp)
                  .clip(RoundedCornerShape(0.75.dp))
                  .background(
                    if (uiState.isHighContextUsage) {
                      warningColor
                    } else {
                      MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    }
                  )
            )
          }
        }

        // Compress label centered beneath the tick mark (0.55 + 0.40 / 2 = 0.75)
        Row(modifier = Modifier.fillMaxWidth()) {
          Spacer(modifier = Modifier.weight(0.55f))
          Box(modifier = Modifier.weight(0.40f), contentAlignment = Alignment.Center) {
            Text(
              text =
                stringResource(
                  R.string.live_metrics_context_compress_mark,
                  compressThresholdPercent,
                ),
              style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp, lineHeight = 10.sp),
              color =
                if (uiState.isHighContextUsage) {
                  warningColor
                } else {
                  MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                },
              maxLines = 1,
            )
          }
          Spacer(modifier = Modifier.weight(0.05f))
        }
      }
    }
  }
}

@Composable
private fun rememberClampedTooltipPositionProvider(): PopupPositionProvider {
  val density = LocalDensity.current
  val marginPx = with(density) { 16.dp.roundToPx() }
  val spacingPx = with(density) { 6.dp.roundToPx() }
  return remember(marginPx, spacingPx) {
    object : PopupPositionProvider {
      override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
      ): IntOffset {
        val centeredX = anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2
        val maxX = (windowSize.width - popupContentSize.width - marginPx).coerceAtLeast(marginPx)
        val x = centeredX.coerceIn(marginPx, maxX)
        val aboveY = anchorBounds.top - popupContentSize.height - spacingPx
        val belowY = anchorBounds.bottom + spacingPx
        val y =
          if (aboveY >= marginPx) {
            aboveY
          } else {
            belowY.coerceAtMost(
              (windowSize.height - popupContentSize.height - marginPx).coerceAtLeast(marginPx)
            )
          }
        return IntOffset(x, y)
      }
    }
  }
}

@Composable
internal fun resolveLiveMetricsUiState(
  metrics: InferenceMetrics,
  isCompactingContext: Boolean = false,
): LiveMetricsUiState {
  val metadata = metrics.metadata
  val inference = metrics.inference
  val statusCode = if (isCompactingContext) InferenceStatus.Code.IDLE else inference.status.code
  val latency = inference.latency
  val tokens = inference.tokens
  val contextMetrics = inference.context
  val memory = metrics.memory
  val battery = metrics.battery

  val hasDecodeSpeed =
    !isCompactingContext && latency.hasDecodeSpeedTps() && latency.decodeSpeedTps > 0f
  val hasPrefillSpeed =
    !isCompactingContext && latency.hasPrefillSpeedTps() && latency.prefillSpeedTps > 0f
  val hasTtft = !isCompactingContext && latency.hasTtftMs() && latency.ttftMs > 0L
  val hasInitDuration = metadata.hasInitDurationMs() && metadata.initDurationMs > 0L

  val showDecodeSpeed =
    hasDecodeSpeed &&
      statusCode != InferenceStatus.Code.IDLE &&
      statusCode != InferenceStatus.Code.PREFILLING

  val speedValue =
    when (statusCode) {
      InferenceStatus.Code.IDLE -> stringResource(R.string.live_metrics_speed_empty)
      InferenceStatus.Code.PREFILLING -> stringResource(R.string.live_metrics_prefilling)
      InferenceStatus.Code.DECODING ->
        if (hasDecodeSpeed) {
          stringResource(R.string.live_metrics_decode_speed_format, latency.decodeSpeedTps)
        } else {
          stringResource(R.string.live_metrics_generating)
        }
      InferenceStatus.Code.SUCCESS,
      InferenceStatus.Code.CANCELLED,
      InferenceStatus.Code.ERROR,
      InferenceStatus.Code.CODE_UNSPECIFIED,
      InferenceStatus.Code.UNRECOGNIZED ->
        if (hasDecodeSpeed) {
          stringResource(R.string.live_metrics_decode_speed_format, latency.decodeSpeedTps)
        } else {
          stringResource(R.string.live_metrics_speed_empty)
        }
    }

  val speedAnnotation =
    if (showDecodeSpeed) {
      stringResource(R.string.live_metrics_speed_decode_annotation)
    } else {
      null
    }

  val speedSubtextLines =
    when {
      hasTtft || hasPrefillSpeed ->
        buildList {
          if (hasTtft) {
            add(stringResource(R.string.live_metrics_ttft_format, latency.ttftMs))
          }
          if (hasPrefillSpeed) {
            add(stringResource(R.string.live_metrics_prefill_speed_format, latency.prefillSpeedTps))
          }
        }
      hasInitDuration ->
        listOf(
          if (metadata.initDurationMs >= MILLIS_PER_SECOND) {
            stringResource(
              R.string.live_metrics_init_seconds_format,
              metadata.initDurationMs / MILLIS_PER_SECOND_FLOAT,
            )
          } else {
            stringResource(R.string.live_metrics_init_ms_format, metadata.initDurationMs)
          }
        )
      else -> emptyList()
    }

  val hasPromptTokens = tokens.hasPromptTokens() && tokens.promptTokens > 0
  val hasOutputTokens = tokens.hasOutputTokens() && tokens.outputTokens > 0
  val hasTotalTokens = tokens.hasTotalTokens() && tokens.totalTokens > 0

  val tokensValue =
    when {
      statusCode == InferenceStatus.Code.IDLE || statusCode == InferenceStatus.Code.PREFILLING ->
        stringResource(R.string.live_metrics_tokens_empty)
      hasPromptTokens && hasOutputTokens ->
        stringResource(
          R.string.live_metrics_tokens_io_format,
          tokens.promptTokens,
          tokens.outputTokens,
        )
      hasOutputTokens ->
        stringResource(R.string.live_metrics_tokens_gen_format, tokens.outputTokens)
      else -> stringResource(R.string.live_metrics_tokens_empty)
    }

  val tokensSubtext =
    when (statusCode) {
      InferenceStatus.Code.IDLE,
      InferenceStatus.Code.PREFILLING -> null
      InferenceStatus.Code.DECODING,
      InferenceStatus.Code.SUCCESS,
      InferenceStatus.Code.CANCELLED,
      InferenceStatus.Code.ERROR,
      InferenceStatus.Code.CODE_UNSPECIFIED,
      InferenceStatus.Code.UNRECOGNIZED ->
        when {
          hasTotalTokens ->
            stringResource(R.string.live_metrics_tokens_total_format, tokens.totalTokens)
          hasOutputTokens ->
            stringResource(R.string.live_metrics_tokens_total_format, tokens.outputTokens)
          else -> null
        }
    }

  val isTurnOrTerminal = inference.status.code != InferenceStatus.Code.IDLE || isCompactingContext
  val hasPeakMemory = isTurnOrTerminal && memory.hasPeakMemoryMb() && memory.peakMemoryMb > 0f
  val ramValue =
    when {
      !hasPeakMemory -> stringResource(R.string.live_metrics_ram_empty)
      memory.peakMemoryMb >= MEGABYTES_PER_GIGABYTE ->
        stringResource(
          R.string.live_metrics_ram_gb_format,
          memory.peakMemoryMb / MEGABYTES_PER_GIGABYTE,
        )
      else -> stringResource(R.string.live_metrics_ram_mb_format, memory.peakMemoryMb)
    }

  val hasTotalMemory = memory.hasTotalDeviceMemoryMb() && memory.totalDeviceMemoryMb > 0f
  val memoryPercent =
    if (hasPeakMemory && hasTotalMemory) {
      (memory.peakMemoryMb / memory.totalDeviceMemoryMb) * 100f
    } else {
      null
    }
  val hasPower = isTurnOrTerminal && battery.hasPeakPowerMw() && battery.peakPowerMw > 0f
  val ramSubtext =
    when {
      memoryPercent != null ->
        stringResource(R.string.live_metrics_ram_percent_format, memoryPercent)
      hasPower ->
        if (battery.peakPowerMw >= MILLIWATTS_PER_WATT) {
          stringResource(
            R.string.live_metrics_power_w_format,
            battery.peakPowerMw / MILLIWATTS_PER_WATT,
          )
        } else {
          stringResource(R.string.live_metrics_power_mw_format, battery.peakPowerMw)
        }
      else -> null
    }

  val hasConsumedContextTokens = !isCompactingContext && contextMetrics.hasConsumedContextTokens()
  val contextUsedTokens =
    if (hasConsumedContextTokens) {
      contextMetrics.consumedContextTokens.coerceAtLeast(0)
    } else {
      null
    }
  val maxContextTokens = contextMetrics.maxContextTokens.coerceAtLeast(0)
  val contextUsedPercent =
    if (contextUsedTokens != null && maxContextTokens > 0) {
      (contextUsedTokens.toFloat() / maxContextTokens.toFloat()) * 100f
    } else {
      0f
    }
  val contextUsageText =
    when {
      isCompactingContext -> stringResource(R.string.live_metrics_compressing_context)
      contextUsedTokens != null ->
        stringResource(
          R.string.live_metrics_context_usage_format,
          contextUsedTokens,
          maxContextTokens,
          contextUsedPercent,
        )
      else -> stringResource(R.string.live_metrics_context_usage_empty_format, maxContextTokens)
    }

  val isHighContextUsage =
    !isCompactingContext &&
      contextUsedTokens != null &&
      SummarizationContextCompactor.isOverTokenThreshold(
        currentTokens = contextUsedTokens,
        maxTokens = maxContextTokens,
      )

  return LiveMetricsUiState(
    speedValue = speedValue,
    speedAnnotation = speedAnnotation,
    speedSubtextLines = speedSubtextLines,
    tokensValue = tokensValue,
    tokensSubtext = tokensSubtext,
    ramValue = ramValue,
    ramSubtext = ramSubtext,
    contextUsageText = contextUsageText,
    contextUsedPercent = contextUsedPercent,
    isHighContextUsage = isHighContextUsage,
    isCompactingContext = isCompactingContext,
  )
}

@Composable
private fun MetricColumn(
  label: String,
  value: String,
  subtextLines: List<String>,
  modifier: Modifier = Modifier,
  valueAnnotation: String? = null,
) {
  Column(modifier = modifier) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      maxLines = 1,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
      Text(
        text = value,
        style =
          MaterialTheme.typography.bodySmall.copy(
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
            fontFeatureSettings = TABULAR_NUMS_FEATURE,
          ),
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        modifier = Modifier.alignByBaseline(),
      )
      if (!valueAnnotation.isNullOrEmpty()) {
        Text(
          text = valueAnnotation,
          style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
          color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
          maxLines = 1,
          modifier = Modifier.alignByBaseline(),
        )
      }
    }
    for (subtext in subtextLines) {
      Text(
        text = subtext,
        style =
          MaterialTheme.typography.labelSmall.copy(
            fontSize = 9.sp,
            lineHeight = 11.sp,
            fontFeatureSettings = TABULAR_NUMS_FEATURE,
          ),
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
        maxLines = 1,
      )
    }
  }
}

private const val TABULAR_NUMS_FEATURE = "tnum"
private const val MILLIS_PER_SECOND: Long = 1000L
private const val MILLIS_PER_SECOND_FLOAT: Float = 1000f
private const val MILLIWATTS_PER_WATT: Float = 1000f
private const val MEGABYTES_PER_GIGABYTE: Float = 1024f
