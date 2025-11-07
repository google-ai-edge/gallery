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

package com.neuralforge.mobile.ui.marketplace

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.neuralforge.mobile.core.ModelFormat
import com.neuralforge.mobile.ui.components.EnhancedModelCard
import com.neuralforge.mobile.ui.components.ModelFormatBadge

/**
 * Model Marketplace UI for browsing and discovering models
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelMarketplaceScreen(
    onModelClick: (ModelInfo) -> Unit,
    onDownloadClick: (ModelInfo) -> Unit,
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedFormat by remember { mutableStateOf<ModelFormat?>(null) }
    var selectedCategory by remember { mutableStateOf<ModelCategory?>(null) }
    var sortBy by remember { mutableStateOf(SortOption.POPULAR) }

    Scaffold(
        topBar = {
            MarketplaceTopBar(
                searchQuery = searchQuery,
                onSearchQueryChange = { searchQuery = it },
                onSortChange = { sortBy = it }
            )
        },
        modifier = modifier
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Filter chips
            FilterSection(
                selectedFormat = selectedFormat,
                selectedCategory = selectedCategory,
                onFormatSelected = { selectedFormat = if (selectedFormat == it) null else it },
                onCategorySelected = { selectedCategory = if (selectedCategory == it) null else it }
            )

            // Model list
            // In real implementation, this would filter/sort from ViewModel
            Text(
                text = "Marketplace implementation placeholder",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MarketplaceTopBar(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onSortChange: (SortOption) -> Unit,
    modifier: Modifier = Modifier
) {
    var showSortMenu by remember { mutableStateOf(false) }

    TopAppBar(
        title = { Text("Model Marketplace") },
        actions = {
            // Sort button
            IconButton(onClick = { showSortMenu = true }) {
                Icon(Icons.Default.Sort, contentDescription = "Sort")
            }

            DropdownMenu(
                expanded = showSortMenu,
                onDismissRequest = { showSortMenu = false }
            ) {
                SortOption.values().forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.displayName) },
                        onClick = {
                            onSortChange(option)
                            showSortMenu = false
                        }
                    )
                }
            }
        },
        modifier = modifier
    )
}

@Composable
private fun FilterSection(
    selectedFormat: ModelFormat?,
    selectedCategory: ModelCategory?,
    onFormatSelected: (ModelFormat) -> Unit,
    onCategorySelected: (ModelCategory) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Format filters
        Text(
            text = "Format",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(
                listOf(
                    ModelFormat.TensorFlowLite,
                    ModelFormat.ONNX,
                    ModelFormat.PyTorchMobile,
                    ModelFormat.LiteRTLM
                )
            ) { format ->
                FilterChip(
                    selected = selectedFormat == format,
                    onClick = { onFormatSelected(format) },
                    label = { Text(format.toString()) }
                )
            }
        }

        // Category filters
        Text(
            text = "Category",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(ModelCategory.values()) { category ->
                FilterChip(
                    selected = selectedCategory == category,
                    onClick = { onCategorySelected(category) },
                    label = { Text(category.displayName) },
                    leadingIcon = {
                        Icon(
                            category.icon,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                )
            }
        }
    }
}

/**
 * Model categories
 */
enum class ModelCategory(
    val displayName: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    LLM("Large Language Models", Icons.Default.Chat),
    VISION("Computer Vision", Icons.Default.RemoveRedEye),
    AUDIO("Audio Processing", Icons.Default.Mic),
    MULTIMODAL("Multimodal", Icons.Default.ViewInAr),
    EMBEDDING("Embeddings", Icons.Default.Hub),
    CLASSIFIER("Classification", Icons.Default.Category)
}

/**
 * Sort options
 */
enum class SortOption(val displayName: String) {
    POPULAR("Most Popular"),
    RECENT("Recently Added"),
    SIZE_ASC("Size (Smallest First)"),
    SIZE_DESC("Size (Largest First)"),
    NAME("Name (A-Z)")
}

/**
 * Model info for marketplace
 */
data class ModelInfo(
    val id: String,
    val name: String,
    val description: String,
    val format: ModelFormat,
    val size: Long,
    val category: ModelCategory,
    val downloads: Int,
    val rating: Float,
    val isDownloaded: Boolean,
    val previewUrl: String? = null
)
