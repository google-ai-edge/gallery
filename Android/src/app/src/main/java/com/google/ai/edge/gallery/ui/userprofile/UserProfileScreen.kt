package com.google.ai.edge.gallery.ui.userprofile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Save // Changed from AddCircle for FAB
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource // Added import
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.google.ai.edge.gallery.R // Added import for R class
// import com.google.ai.edge.gallery.data.UserProfile // Not directly used in this file
import com.google.ai.edge.gallery.ui.ViewModelProvider // Assuming ViewModelProvider.Factory
// import com.google.ai.edge.gallery.ui.common.LocalAppContainer // Not used
// import com.google.ai.edge.gallery.ui.navigation.GalleryDestinations // Not used here

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserProfileScreen(
    navController: NavController,
    viewModelFactory: ViewModelProvider.Factory, // Adjusted to typical factory naming
    viewModel: UserProfileViewModel = viewModel(factory = viewModelFactory)
) {
    val userProfile by viewModel.userProfile.collectAsState()
    val scrollState = rememberScrollState()
    var showSaveConfirmation by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.user_profile_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.user_profile_back_button_desc))
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    viewModel.saveUserProfile()
                    showSaveConfirmation = true
                },
                icon = { Icon(Icons.Filled.Save, contentDescription = stringResource(R.string.user_profile_save_button)) },
                text = { Text(stringResource(R.string.user_profile_save_button)) }
            )
        },
        floatingActionButtonPosition = FabPosition.Center
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(scrollState)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Text("Your Information", style = MaterialTheme.typography.headlineSmall) // This was not in strings.xml, can be added if needed

            OutlinedTextField(
                value = userProfile.name ?: "",
                onValueChange = { viewModel.updateName(it) },
                label = { Text(stringResource(R.string.user_profile_name_label)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = userProfile.summary ?: "",
                onValueChange = { viewModel.updateSummary(it) },
                label = { Text(stringResource(R.string.user_profile_summary_label)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3
            )

            // Skills Section
            EditableListSection(
                title = stringResource(R.string.user_profile_skills_title),
                items = userProfile.skills,
                onAddItem = { viewModel.addSkill() },
                onRemoveItem = { index -> viewModel.removeSkill(index) },
                onUpdateItem = { index, text -> viewModel.updateSkill(index, text) },
                itemLabel = { index -> stringResource(R.string.user_profile_skill_label_prefix, index + 1) },
                addButtonText = stringResource(R.string.user_profile_add_skill_button),
                emptyListText = stringResource(R.string.user_profile_no_skills),
                removeItemDesc = stringResource(R.string.user_profile_remove_item_desc)
            )

            // Experience Section
            EditableListSection(
                title = stringResource(R.string.user_profile_experience_title),
                items = userProfile.experience,
                onAddItem = { viewModel.addExperience() },
                onRemoveItem = { index -> viewModel.removeExperience(index) },
                onUpdateItem = { index, text -> viewModel.updateExperience(index, text) },
                itemLabel = { index -> stringResource(R.string.user_profile_experience_label_prefix, index + 1) },
                isMultiLine = true,
                addButtonText = stringResource(R.string.user_profile_add_experience_button),
                emptyListText = stringResource(R.string.user_profile_no_experience),
                removeItemDesc = stringResource(R.string.user_profile_remove_item_desc)
            )

            Spacer(Modifier.height(60.dp)) // Space for FAB
        }

        if (showSaveConfirmation) {
            AlertDialog(
                onDismissRequest = { showSaveConfirmation = false },
                title = { Text(stringResource(R.string.user_profile_saved_dialog_title)) },
                text = { Text(stringResource(R.string.user_profile_saved_dialog_message)) },
                confirmButton = {
                    Button(onClick = {
                        showSaveConfirmation = false
                        // Consider navigating back or giving other options
                        // navController.popBackStack()
                    }) { Text(stringResource(R.string.dialog_ok_button)) }
                }
            )
        }
    }
}

@Composable
fun EditableListSection(
    title: String,
    items: List<String>,
    onAddItem: () -> Unit,
    onRemoveItem: (Int) -> Unit,
    onUpdateItem: (Int, String) -> Unit,
    itemLabel: (Int) -> String,
    isMultiLine: Boolean = false,
    addButtonText: String,
    emptyListText: String,
    removeItemDesc: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Button(onClick = onAddItem) {
                Icon(Icons.Filled.AddCircle, contentDescription = addButtonText)
                Spacer(Modifier.width(4.dp))
                Text(addButtonText)
            }
        }
        items.forEachIndexed { index, item ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = item,
                    onValueChange = { onUpdateItem(index, it) },
                    label = { Text(itemLabel(index)) },
                    modifier = Modifier.weight(1f),
                    minLines = if (isMultiLine) 2 else 1,
                    maxLines = if (isMultiLine) 5 else 1
                )
                IconButton(onClick = { onRemoveItem(index) }) {
                    Icon(Icons.Filled.Delete, contentDescription = removeItemDesc)
                }
            }
        }
        if (items.isEmpty()) {
            Text(emptyListText, style = MaterialTheme.typography.bodySmall)
        }
    }
}
