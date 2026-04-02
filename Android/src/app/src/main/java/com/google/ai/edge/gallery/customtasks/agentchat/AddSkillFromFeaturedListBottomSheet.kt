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

package com.google.ai.edge.gallery.customtasks.agentchat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.AllowedSkill
import kotlinx.coroutines.launch

/** A ModalBottomSheet Composable for displaying and adding skills from a featured list. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddSkillFromFeatureListBottomSheet(
  skillManagerViewModel: SkillManagerViewModel,
  onDismiss: () -> Unit,
  onSkillAdded: () -> Unit,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  var searchQuery by remember { mutableStateOf("") }
  var showDisclaimerDialog by remember { mutableStateOf(false) }
  var skillToAdd by remember { mutableStateOf<AllowedSkill?>(null) }
  var skillValidationErrors by remember { mutableStateOf(emptyMap<String, String>()) }
  var validatingSkills by remember { mutableStateOf(emptySet<String>()) }
  val uriHandler = LocalUriHandler.current
  val scope = rememberCoroutineScope()
  val uiState by skillManagerViewModel.uiState.collectAsState()
  val addedSkillNames = remember(uiState.skills) { uiState.skills.map { it.skill.name }.toSet() }

  // Filter the featured skills based on the search query.
  val filteredSkills =
    remember(searchQuery, uiState.featuredSkills) {
      val trimmedQuery = searchQuery.trim().lowercase()
      if (trimmedQuery.isBlank()) {
        // Clear errors when search query is empty.
        skillValidationErrors = emptyMap()
        uiState.featuredSkills
      } else {
        // Filter skills where the name or description contains the trimmed query.
        uiState.featuredSkills.filter { skill ->
          skill.name.lowercase().contains(trimmedQuery) ||
            skill.description.lowercase().contains(trimmedQuery)
        }
      }
    }

  // Handles the action of adding a skill.
  val handleAddSkill: (AllowedSkill) -> Unit = { skill ->
    val url = skill.skillUrl
    // Check if the skill's host is approved.
    //
    // If approved, start validation and add the skill.
    if (isHostApproved(url)) {
      validatingSkills = validatingSkills + url
      skillManagerViewModel.validateAndAddSkillFromUrl(
        url = url,
        onSuccess = {
          validatingSkills = validatingSkills - url
          onDismiss()
          onSkillAdded()
        },
        onValidationError = { error ->
          validatingSkills = validatingSkills - url
          skillValidationErrors = skillValidationErrors + (url to error)
        },
      )
    }
    // If not approved, show a disclaimer dialog.
    else {
      skillToAdd = skill
      showDisclaimerDialog = true
    }
  }

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = MaterialTheme.colorScheme.surfaceContainer,
  ) {
    Column(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
      // Header section with title, description, and close button.
      Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = stringResource(R.string.featured_skills_title),
            style = MaterialTheme.typography.titleLarge,
          )
          Text(
            stringResource(R.string.featured_skills_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        IconButton(
          modifier = Modifier.padding(end = 3.dp),
          onClick = {
            scope.launch {
              sheetState.hide()
              onDismiss()
            }
          },
        ) {
          Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.cd_close_icon))
        }
      }

      // Display loading, error, or the skill list.
      //
      // Show a loading indicator while fetching the skill allowlist.
      if (uiState.loadingSkillAllowlist) {
        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
          Row(
            modifier = Modifier.align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Text(
              stringResource(R.string.loading_skills_allowlist),
              style = MaterialTheme.typography.bodyMedium,
            )
          }
        }
      }
      // Show an error message if fetching the allowlist failed.
      else if (uiState.skillAllowlistError != null) {
        Text(
          text = uiState.skillAllowlistError!!,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.error,
          modifier = Modifier.padding(vertical = 16.dp),
        )
      } else {
        // Search bar for filtering skills.
        TextField(
          value = searchQuery,
          onValueChange = { searchQuery = it },
          modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp, top = 8.dp),
          shape = CircleShape,
          placeholder = { Text(stringResource(R.string.search_skill)) },
          leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
          trailingIcon = {
            // Show a clear button if the search query is not empty.
            if (searchQuery.trim().isNotEmpty()) {
              IconButton(onClick = { searchQuery = "" }) {
                Icon(Icons.Outlined.Cancel, contentDescription = null)
              }
            }
          },
          singleLine = true,
          colors =
            TextFieldDefaults.colors(
              focusedIndicatorColor = Color.Transparent,
              unfocusedIndicatorColor = Color.Transparent,
              disabledIndicatorColor = Color.Transparent,
            ),
        )

        // LazyColumn to display the list of featured skills.
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
          items(filteredSkills) { skill ->
            val validationError = skillValidationErrors[skill.skillUrl]
            FeaturedSkillItem(
              skill = skill,
              uriHandler = uriHandler,
              onAddClick = handleAddSkill,
              validationError = validationError,
              isAdding = validatingSkills.contains(skill.skillUrl),
              isSkillAdded = addedSkillNames.contains(skill.name),
            )
          }
        }
      }
    }
  }

  // Disclaimer dialog shown when adding a skill from an unapproved host.
  if (showDisclaimerDialog) {
    AddSkillDisclaimerDialog(
      onDismiss = {
        showDisclaimerDialog = false
        skillToAdd = null
      },
      onConfirm = {
        // If confirmed, proceed with validation and adding the skill.
        skillToAdd?.let { skill ->
          val url = skill.skillUrl
          validatingSkills = validatingSkills + url
          skillManagerViewModel.validateAndAddSkillFromUrl(
            url = url,
            onSuccess = {
              validatingSkills = validatingSkills - url
              onDismiss()
              onSkillAdded()
            },
            onValidationError = { error ->
              validatingSkills = validatingSkills - url
              skillValidationErrors = skillValidationErrors + (url to error)
            },
          )
        }
        showDisclaimerDialog = false
        skillToAdd = null
      },
    )
  }
}

/** Composable for displaying a single featured skill item in the list. */
@Composable
private fun FeaturedSkillItem(
  skill: AllowedSkill,
  uriHandler: androidx.compose.ui.platform.UriHandler,
  onAddClick: (AllowedSkill) -> Unit,
  validationError: String? = null,
  isAdding: Boolean = false,
  isSkillAdded: Boolean = false,
) {
  Row(
    modifier =
      Modifier.fillMaxWidth()
        .clip(shape = RoundedCornerShape(20.dp))
        .background(MaterialTheme.colorScheme.surfaceContainerLowest)
        .padding(vertical = 12.dp)
        .padding(start = 16.dp, end = 8.dp),
    verticalAlignment = Alignment.Top,
  ) {
    Column(modifier = Modifier.weight(1f)) {
      // Name
      Text(skill.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)

      // Attribution
      skill.attributionLabel?.let { label ->
        val hasUrl = !skill.attributionUrl.isNullOrBlank()
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(2.dp),
          modifier = Modifier.padding(top = 2.dp),
        ) {
          if (hasUrl) {
            Text(
              label,
              style =
                MaterialTheme.typography.bodySmall.copy(
                  color = MaterialTheme.colorScheme.primary,
                  textDecoration = TextDecoration.Underline,
                ),
              modifier = Modifier.clickable { skill.attributionUrl?.let { uriHandler.openUri(it) } },
            )
            Icon(
              Icons.AutoMirrored.Outlined.OpenInNew,
              contentDescription = null,
              modifier = Modifier.size(14.dp),
              tint = MaterialTheme.colorScheme.primary,
            )
          } else {
            Text(
              label,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }

      // Description
      Text(
        skill.description,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp),
      )

      // Validation Error
      validationError?.let { error ->
        Text(
          text = error,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
          modifier = Modifier.padding(top = 8.dp),
        )
      }
    }

    // Add Button
    Box(modifier = Modifier.padding(top = 4.dp).height(32.dp).padding(end = 8.dp)) {
      if (isAdding) {
        CircularProgressIndicator(
          modifier = Modifier.size(24.dp).align(Alignment.Center),
          strokeWidth = 2.dp,
          color = MaterialTheme.colorScheme.primary,
        )
      } else {
        if (isSkillAdded) {
          FilledTonalButton(
            onClick = { /* Do nothing */ },
            contentPadding = BUTTON_CONTENT_PADDING,
            enabled = false, // Greyed out
          ) {
            Text(
              stringResource(R.string.added),
              style = MaterialTheme.typography.labelMedium,
              modifier = Modifier.padding(start = 4.dp),
            )
          }
        } else {
          FilledTonalButton(
            onClick = { onAddClick(skill) },
            contentPadding = BUTTON_CONTENT_PADDING,
          ) {
            Icon(
              Icons.Outlined.Add,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.size(18.dp),
            )
            Text(
              stringResource(R.string.add),
              style = MaterialTheme.typography.labelMedium,
              modifier = Modifier.padding(start = 4.dp),
            )
          }
        }
      }
    }
  }
}
