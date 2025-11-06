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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neuralforge.mobile.core.ModelFormat

/**
 * Badge component displaying model format with color coding
 */
@Composable
fun ModelFormatBadge(
    format: ModelFormat,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    val (backgroundColor, textColor, displayText) = when (format) {
        is ModelFormat.TensorFlowLite -> Triple(
            Color(0xFF4285F4), // Google Blue
            Color.White,
            if (compact) "TFLite" else "TensorFlow Lite"
        )
        is ModelFormat.ONNX -> Triple(
            Color(0xFF00A4EF), // Azure Blue
            Color.White,
            "ONNX"
        )
        is ModelFormat.PyTorchMobile -> Triple(
            Color(0xFFEE4C2C), // PyTorch Orange
            Color.White,
            if (compact) "PyTorch" else "PyTorch Mobile"
        )
        is ModelFormat.LiteRTLM -> Triple(
            Color(0xFF34A853), // Google Green
            Color.White,
            if (compact) "LiteRT" else "LiteRT-LM"
        )
        is ModelFormat.Unknown -> Triple(
            Color(0xFF9E9E9E), // Gray
            Color.White,
            "Unknown"
        )
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(backgroundColor)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = displayText,
            color = textColor,
            fontSize = if (compact) 10.sp else 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp
        )
    }
}

/**
 * Larger badge with icon for model cards
 */
@Composable
fun ModelFormatChip(
    format: ModelFormat,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val (backgroundColor, textColor, displayText, description) = when (format) {
        is ModelFormat.TensorFlowLite -> QuadTuple(
            Color(0xFF4285F4),
            Color.White,
            "TensorFlow Lite",
            "Google's mobile ML framework"
        )
        is ModelFormat.ONNX -> QuadTuple(
            Color(0xFF00A4EF),
            Color.White,
            "ONNX",
            "Open Neural Network Exchange"
        )
        is ModelFormat.PyTorchMobile -> QuadTuple(
            Color(0xFFEE4C2C),
            Color.White,
            "PyTorch Mobile",
            "Facebook's mobile ML framework"
        )
        is ModelFormat.LiteRTLM -> QuadTuple(
            Color(0xFF34A853),
            Color.White,
            "LiteRT-LM",
            "Google's optimized LLM runtime"
        )
        is ModelFormat.Unknown -> QuadTuple(
            Color(0xFF9E9E9E),
            Color.White,
            "Unknown",
            "Format not recognized"
        )
    }

    if (onClick != null) {
        AssistChip(
            onClick = onClick,
            label = {
                Text(
                    text = displayText,
                    fontWeight = FontWeight.SemiBold
                )
            },
            colors = AssistChipDefaults.assistChipColors(
                containerColor = backgroundColor,
                labelColor = textColor
            ),
            modifier = modifier
        )
    } else {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(8.dp))
                .background(backgroundColor)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Column {
                Text(
                    text = displayText,
                    color = textColor,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = description,
                    color = textColor.copy(alpha = 0.8f),
                    fontSize = 10.sp
                )
            }
        }
    }
}

/**
 * Helper data class for quad values
 */
private data class QuadTuple<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D
)

/**
 * Small badge for lists
 */
@Composable
fun ModelFormatIndicator(
    format: ModelFormat,
    modifier: Modifier = Modifier
) {
    val color = when (format) {
        is ModelFormat.TensorFlowLite -> Color(0xFF4285F4)
        is ModelFormat.ONNX -> Color(0xFF00A4EF)
        is ModelFormat.PyTorchMobile -> Color(0xFFEE4C2C)
        is ModelFormat.LiteRTLM -> Color(0xFF34A853)
        is ModelFormat.Unknown -> Color(0xFF9E9E9E)
    }

    Box(
        modifier = modifier
            .size(12.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(color)
    )
}
