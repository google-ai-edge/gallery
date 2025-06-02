package com.google.ai.edge.gallery.ui.persona

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource // Added import
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.google.ai.edge.gallery.R // Added import for R class
import com.google.ai.edge.gallery.data.Persona
import com.google.ai.edge.gallery.ui.ViewModelProvider // For ViewModelProvider.Factory

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonaManagementScreen(
    navController: NavController,
    viewModelFactory: ViewModelProvider.Factory, // Changed from ViewModelProviderFactory
    viewModel: PersonaViewModel = viewModel(factory = viewModelFactory)
) {
    val personas by viewModel.personas.collectAsState()
    val activePersonaId by viewModel.activePersonaId.collectAsState()
    var showAddPersonaDialog by remember { mutableStateOf(false) }
    var editingPersona by remember { mutableStateOf<Persona?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.persona_management_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.user_profile_back_button_desc)) // Reusing from user profile
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { editingPersona = null; showAddPersonaDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.persona_management_add_fab_desc))
            }
        }
    ) { paddingValues ->
        LazyColumn(
            contentPadding = paddingValues,
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (personas.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.persona_management_no_personas),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp).fillMaxWidth()
                    )
                }
            }
            items(personas, key = { it.id }) { persona ->
                PersonaItem(
                    persona = persona,
                    isActive = persona.id == activePersonaId,
                    onSetAsActive = { viewModel.setActivePersona(persona.id) },
                    onEdit = { editingPersona = persona; showAddPersonaDialog = true },
                    onDelete = { viewModel.deletePersona(persona.id) },
                    isDefault = persona.isDefault // Pass isDefault
                )
            }
        }

        if (showAddPersonaDialog) {
            AddEditPersonaDialog(
                personaToEdit = editingPersona,
                onDismiss = { showAddPersonaDialog = false },
                onConfirm = { name, prompt ->
                    if (editingPersona == null) {
                        viewModel.addPersona(name, prompt)
                    } else {
                        viewModel.updatePersona(editingPersona!!.copy(name = name, prompt = prompt))
                    }
                    showAddPersonaDialog = false
                    editingPersona = null
                }
            )
        }
    }
}

@Composable
fun PersonaItem(
    persona: Persona,
    isActive: Boolean,
    onSetAsActive: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    isDefault: Boolean // Added isDefault
) {
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSetAsActive() },
        elevation = CardDefaults.cardElevation(if (isActive) 4.dp else 1.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isActive) Icons.Filled.Star else Icons.Filled.Person,
                    contentDescription = stringResource(if (isActive) R.string.persona_item_active_desc else R.string.persona_item_inactive_desc),
                tint = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 16.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isDefault) "${persona.name} ${stringResource(id = R.string.persona_item_default_suffix)}" else persona.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                Text(persona.prompt.take(100) + if (persona.prompt.length > 100) "..." else "", style = MaterialTheme.typography.bodySmall)
            }
            if (!isDefault) { // Only show edit/delete for non-default personas
                IconButton(onClick = onEdit) {
                        Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.persona_item_edit_desc))
                }
                IconButton(onClick = { showDeleteConfirmDialog = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.persona_item_delete_desc))
                }
            }
        }
    }
    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
                title = { Text(stringResource(R.string.persona_delete_dialog_title)) },
                text = { Text(stringResource(R.string.persona_delete_dialog_message, persona.name)) },
                confirmButton = { Button(onClick = { onDelete(); showDeleteConfirmDialog = false }) { Text(stringResource(R.string.persona_delete_dialog_confirm_button)) } },
                dismissButton = { Button(onClick = { showDeleteConfirmDialog = false }) { Text(stringResource(R.string.persona_delete_dialog_cancel_button)) } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditPersonaDialog(
    personaToEdit: Persona?,
    onDismiss: () -> Unit,
    onConfirm: (name: String, prompt: String) -> Unit
) {
    var name by remember { mutableStateOf(personaToEdit?.name ?: "") }
    var prompt by remember { mutableStateOf(personaToEdit?.prompt ?: "") }
    val isEditMode = personaToEdit != null
    val isDefaultPersona = personaToEdit?.isDefault ?: false

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (isEditMode) R.string.persona_add_edit_dialog_edit_title else R.string.persona_add_edit_dialog_add_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.persona_add_edit_dialog_name_label)) },
                    singleLine = true,
                    readOnly = isDefaultPersona // Name is read-only for default personas
                )
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text(stringResource(R.string.persona_add_edit_dialog_prompt_label)) },
                    modifier = Modifier.heightIn(min = 150.dp, max = 300.dp) // Allow prompt to grow
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { if (name.isNotBlank() && prompt.isNotBlank()) onConfirm(name, prompt) },
                enabled = name.isNotBlank() && prompt.isNotBlank()
            ) { Text(stringResource(if (isEditMode) R.string.persona_add_edit_dialog_save_button else R.string.persona_add_edit_dialog_add_button)) }
        },
        dismissButton = { Button(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel_button)) } }
    )
}
