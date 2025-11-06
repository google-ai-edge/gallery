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

package com.neuralforge.mobile.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.neuralforge.mobile.core.Accelerator
import com.neuralforge.mobile.core.HardwareCapabilities

/**
 * GPU and Hardware Acceleration Settings Screen
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GPUSettingsScreen(
    hardwareCapabilities: HardwareCapabilities?,
    selectedAccelerator: Accelerator,
    onAcceleratorSelected: (Accelerator) -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Hardware Acceleration") },
                navigationIcon = {
                    IconButton(onClick = { /* Navigate back */ }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        modifier = modifier
    ) { paddingValues ->
        if (hardwareCapabilities == null) {
            // Loading state
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Hardware info card
                item {
                    HardwareInfoCard(hardwareCapabilities)
                }

                // Accelerator selection
                item {
                    Text(
                        text = "Select Accelerator",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Accelerator options
                items(
                    listOf(
                        AcceleratorOption(Accelerator.CPU, hardwareCapabilities.cpu != null),
                        AcceleratorOption(Accelerator.GPU, hardwareCapabilities.gpu != null),
                        AcceleratorOption(Accelerator.NNAPI, hardwareCapabilities.nnapi != null),
                        AcceleratorOption(Accelerator.NPU, hardwareCapabilities.npu != null),
                        AcceleratorOption(Accelerator.DSP, hardwareCapabilities.dsp != null)
                    )
                ) { option ->
                    AcceleratorCard(
                        accelerator = option.accelerator,
                        isAvailable = option.isAvailable,
                        isSelected = selectedAccelerator == option.accelerator,
                        onClick = { if (option.isAvailable) onAcceleratorSelected(option.accelerator) }
                    )
                }
            }
        }
    }
}

@Composable
private fun HardwareInfoCard(
    capabilities: HardwareCapabilities,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Device Hardware",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            // CPU Info
            HardwareInfoRow(
                icon = Icons.Default.Memory,
                label = "CPU",
                value = "${capabilities.cpu.name} (${capabilities.cpu.cores} cores)"
            )

            // GPU Info
            if (capabilities.gpu != null) {
                HardwareInfoRow(
                    icon = Icons.Default.Vrpano,
                    label = "GPU",
                    value = capabilities.gpu.name
                )
            }

            // NPU Info
            if (capabilities.npu != null) {
                HardwareInfoRow(
                    icon = Icons.Default.Psychology,
                    label = "NPU",
                    value = capabilities.npu.name
                )
            }

            // DSP Info
            if (capabilities.dsp != null) {
                HardwareInfoRow(
                    icon = Icons.Default.GraphicEq,
                    label = "DSP",
                    value = capabilities.dsp.name
                )
            }

            // NNAPI Info
            if (capabilities.nnapi != null) {
                HardwareInfoRow(
                    icon = Icons.Default.Android,
                    label = "NNAPI",
                    value = "Version ${capabilities.nnapi.version}"
                )
            }
        }
    }
}

@Composable
private fun HardwareInfoRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun AcceleratorCard(
    accelerator: Accelerator,
    isAvailable: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        enabled = isAvailable,
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    getAcceleratorIcon(accelerator),
                    contentDescription = null,
                    tint = if (isAvailable) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )

                Column {
                    Text(
                        text = accelerator.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = getAcceleratorDescription(accelerator),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (!isAvailable) {
                        Text(
                            text = "Not available on this device",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            if (isSelected && isAvailable) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

private fun getAcceleratorIcon(accelerator: Accelerator): androidx.compose.ui.graphics.vector.ImageVector {
    return when (accelerator) {
        Accelerator.CPU -> Icons.Default.Memory
        Accelerator.GPU -> Icons.Default.Vrpano
        Accelerator.NPU -> Icons.Default.Psychology
        Accelerator.DSP -> Icons.Default.GraphicEq
        Accelerator.NNAPI -> Icons.Default.Android
    }
}

private fun getAcceleratorDescription(accelerator: Accelerator): String {
    return when (accelerator) {
        Accelerator.CPU -> "General purpose processor"
        Accelerator.GPU -> "Graphics processor for parallel operations"
        Accelerator.NPU -> "Dedicated neural processing unit"
        Accelerator.DSP -> "Digital signal processor"
        Accelerator.NNAPI -> "Android Neural Networks API"
    }
}

private data class AcceleratorOption(
    val accelerator: Accelerator,
    val isAvailable: Boolean
)
