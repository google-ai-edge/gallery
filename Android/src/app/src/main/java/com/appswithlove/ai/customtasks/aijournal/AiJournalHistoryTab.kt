package com.appswithlove.ai.customtasks.aijournal

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.appswithlove.ai.R
import com.appswithlove.ai.customtasks.aijournal.data.EntryWithEntities
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val PersonColor = Color(0xFF2196F3)
private val ActivityColor = Color(0xFF4CAF50)
private val MoodColor = Color(0xFFFF9800)
private val LocationColor = Color(0xFF9C27B0)
private val EventColor = Color(0xFF607D8B)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiJournalHistoryTab(
  viewModel: AiJournalViewModel,
  bottomPadding: Dp,
) {
  val uiState by viewModel.uiState.collectAsState()
  var searchQuery by remember { mutableStateOf(uiState.filterQuery) }
  var showFilters by remember { mutableStateOf(false) }
  val expandedDays = remember { mutableStateMapOf<String, Boolean>() }

  val dateFormat = remember { SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault()) }
  val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

  // Group entries by day
  val entriesByDay = remember(uiState.historyEntries) {
    uiState.historyEntries.groupBy { entry ->
      dateFormat.format(Date(entry.entry.timestamp))
    }
  }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .padding(bottom = bottomPadding),
  ) {
    // Search bar
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      OutlinedTextField(
        value = searchQuery,
        onValueChange = {
          searchQuery = it
          viewModel.setFilter(query = it)
        },
        modifier = Modifier.weight(1f),
        placeholder = { Text(stringResource(R.string.aijournal_search_placeholder)) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.aijournal_search)) },
        trailingIcon = {
          if (searchQuery.isNotBlank()) {
            IconButton(onClick = {
              searchQuery = ""
              viewModel.setFilter(query = "")
            }) {
              Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.clear))
            }
          }
        },
        shape = RoundedCornerShape(24.dp),
        singleLine = true,
      )
      IconButton(onClick = { showFilters = !showFilters }) {
        Icon(
          Icons.Outlined.FilterList,
          contentDescription = stringResource(R.string.aijournal_filters),
          tint = if (uiState.filterPerson != null || uiState.filterActivity != null || uiState.filterMood != null || uiState.filterLocation != null)
            MaterialTheme.colorScheme.primary
          else MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }

    // Filter chips
    AnimatedVisibility(
      visible = showFilters,
      enter = expandVertically(),
      exit = shrinkVertically(),
    ) {
      Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        FilterRow(stringResource(R.string.aijournal_filter_person), uiState.availablePeople, uiState.filterPerson) {
          viewModel.setFilter(person = it)
        }
        FilterRow(stringResource(R.string.aijournal_filter_activity), uiState.availableActivities, uiState.filterActivity) {
          viewModel.setFilter(activity = it)
        }
        FilterRow(stringResource(R.string.aijournal_filter_mood), uiState.availableMoods, uiState.filterMood) {
          viewModel.setFilter(mood = it)
        }
        FilterRow(stringResource(R.string.aijournal_filter_location), uiState.availableLocations, uiState.filterLocation) {
          viewModel.setFilter(location = it)
        }

        if (uiState.filterPerson != null || uiState.filterActivity != null || uiState.filterMood != null || uiState.filterLocation != null) {
          TextButton(onClick = { viewModel.clearFilters() }) {
            Text(stringResource(R.string.aijournal_clear_all_filters))
          }
        }
        Spacer(Modifier.height(8.dp))
      }
    }

    // Day cards
    if (entriesByDay.isEmpty()) {
      Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          text = if (searchQuery.isNotBlank() || uiState.filterPerson != null) stringResource(R.string.aijournal_no_matching_entries) else stringResource(R.string.aijournal_no_entries_yet),
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        )
      }
    } else {
      LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        item { Spacer(Modifier.height(4.dp)) }

        entriesByDay.forEach { (dayLabel, entries) ->
          item(key = dayLabel) {
            DayCard(
              dayLabel = dayLabel,
              entries = entries,
              timeFormat = timeFormat,
              expanded = expandedDays[dayLabel] == true,
              onToggle = { expandedDays[dayLabel] = !(expandedDays[dayLabel] ?: false) },
            )
          }
        }

        item { Spacer(Modifier.height(8.dp)) }
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterRow(
  label: String,
  options: List<String>,
  selected: String?,
  onSelect: (String?) -> Unit,
) {
  if (options.isEmpty()) return

  var expanded by remember { mutableStateOf(false) }

  Row(
    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = "$label:",
      style = MaterialTheme.typography.labelMedium,
      modifier = Modifier.width(72.dp),
    )

    val anyLabel = stringResource(R.string.aijournal_filter_any)
    Box {
      SuggestionChip(
        onClick = { expanded = true },
        label = { Text(selected ?: anyLabel) },
      )
      DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(
          text = { Text(anyLabel) },
          onClick = { onSelect(null); expanded = false },
        )
        options.forEach { option ->
          DropdownMenuItem(
            text = { Text(option) },
            onClick = { onSelect(option); expanded = false },
          )
        }
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DayCard(
  dayLabel: String,
  entries: List<EntryWithEntities>,
  timeFormat: SimpleDateFormat,
  expanded: Boolean,
  onToggle: () -> Unit,
) {
  Card(
    onClick = onToggle,
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(
      containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ),
  ) {
    Column(modifier = Modifier.padding(16.dp)) {
      // Date header
      Text(
        text = dayLabel,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
      )
      Text(
        text = pluralStringResource(R.plurals.aijournal_entries_count, entries.size, entries.size),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
      )

      Spacer(Modifier.height(8.dp))

      // Per-entry: text animates in/out, chips always visible
      entries.forEach { entryWithEntities ->
        Column(modifier = Modifier.padding(top = 4.dp)) {
          // Time + raw text — only when expanded
          AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
          ) {
            Column(modifier = Modifier.padding(bottom = 4.dp)) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                  text = timeFormat.format(Date(entryWithEntities.entry.timestamp)),
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
                if (entryWithEntities.entry.inputType != "text") {
                  Spacer(Modifier.width(8.dp))
                  AssistChip(
                    onClick = {},
                    label = {
                      Text(
                        text = if (entryWithEntities.entry.inputType == "voice_gemma") "Gemma" else "STT",
                        style = MaterialTheme.typography.labelSmall,
                      )
                    },
                    modifier = Modifier.height(20.dp),
                  )
                }
              }
              Text(
                text = entryWithEntities.entry.rawText,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 2.dp),
              )
            }
          }

          // Chips — always visible
          if (entryWithEntities.entities.isNotEmpty()) {
            FlowRow(
              horizontalArrangement = Arrangement.spacedBy(4.dp),
              verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
              entryWithEntities.entities.forEach { entity ->
                EntityChip(entity.entityType, entity.entityValue)
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun EntityChip(type: String, value: String) {
  val (color, prefix) = when (type) {
    "PERSON" -> PersonColor to ""
    "ACTIVITY" -> ActivityColor to ""
    "MOOD" -> MoodColor to ""
    "LOCATION" -> LocationColor to ""
    "EVENT" -> EventColor to ""
    else -> MaterialTheme.colorScheme.outline to ""
  }

  AssistChip(
    onClick = {},
    label = { Text(value, style = MaterialTheme.typography.labelSmall) },
    modifier = Modifier.height(24.dp),
    colors = AssistChipDefaults.assistChipColors(
      containerColor = color.copy(alpha = 0.12f),
      labelColor = color,
    ),
  )
}
