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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.OutlinedTextFieldDefaults.FocusedBorderThickness
import androidx.compose.material3.OutlinedTextFieldDefaults.UnfocusedBorderThickness
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.google.ai.edge.gallery.R
import java.net.URI

private val APPROVED_SKILL_HOSTS = listOf("google-ai-edge.github.io")

@Composable
fun AddSkillFromUrlDialog(
  skillManagerViewModel: SkillManagerViewModel,
  onDismissRequest: () -> Unit,
  onSuccess: () -> Unit,
) {
  val uiState by skillManagerViewModel.uiState.collectAsState()
  val validating = uiState.validating
  val validationError = uiState.validationError

  val interactionSource = remember { MutableInteractionSource() }
  var textFieldValue by remember { mutableStateOf(TextFieldValue("")) }
  var showDisclaimerDialog by remember { mutableStateOf(false) }

  val validateAndAddSkill: (String) -> Unit = { url ->
    skillManagerViewModel.validateAndAddSkillFromUrl(
      url = url,
      onSuccess = {
        onDismissRequest()
        onSuccess()
      },
      onValidationError = { error ->
        // Select all text on error
        textFieldValue = textFieldValue.copy(selection = TextRange(0, textFieldValue.text.length))
      },
    )
  }

  Dialog(onDismissRequest = { if (!validating) onDismissRequest() }) {
    val focusManager = LocalFocusManager.current
    Card(
      modifier =
        Modifier.fillMaxWidth().clickable(
          interactionSource = interactionSource,
          indication = null, // Disable the ripple effect
        ) {
          focusManager.clearFocus()
        },
      shape = RoundedCornerShape(16.dp),
    ) {
      Column(
        modifier = Modifier.padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        Text(
          stringResource(R.string.add_skill_from_url_dialog_title),
          style = MaterialTheme.typography.titleMedium,
          modifier = Modifier.padding(bottom = 8.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Text(
            stringResource(R.string.enter_skill_url),
            style = MaterialTheme.typography.labelMedium,
          )
          BasicTextField(
            value = textFieldValue,
            onValueChange = { newValue ->
              val oldText = textFieldValue.text
              textFieldValue = newValue
              // Clear error on text change
              if (newValue.text != oldText) {
                skillManagerViewModel.setValidationError(null)
              }
            },
            modifier = Modifier.fillMaxWidth(),
            textStyle =
              MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface),
            maxLines = 3,
            decorationBox = { innerTextField ->
              OutlinedTextFieldDefaults.DecorationBox(
                value = textFieldValue.text,
                innerTextField = innerTextField,
                enabled = true,
                singleLine = false,
                visualTransformation = VisualTransformation.None,
                interactionSource = remember { MutableInteractionSource() },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                container = {
                  OutlinedTextFieldDefaults.Container(
                    enabled = true,
                    isError = false,
                    interactionSource = remember { MutableInteractionSource() },
                    colors = OutlinedTextFieldDefaults.colors(),
                    shape = OutlinedTextFieldDefaults.shape,
                    focusedBorderThickness = FocusedBorderThickness,
                    unfocusedBorderThickness = UnfocusedBorderThickness,
                  )
                },
              )
            },
          )
          validationError?.let { error ->
            Text(
              text = error,
              color = MaterialTheme.colorScheme.error,
              style = MaterialTheme.typography.bodySmall,
            )
          }
        }
        // Show spinner when validating.
        if (validating) {
          Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            CircularProgressIndicator(
              modifier = Modifier.size(20.dp),
              strokeWidth = 2.dp,
              color = MaterialTheme.colorScheme.primary,
            )
          }
        }
        // Show buttons when not validating.
        else {
          Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.End,
          ) {
            OutlinedButton(onClick = { onDismissRequest() }) {
              Text(stringResource(R.string.cancel))
            }
            Spacer(modifier = Modifier.width(4.dp))
            Button(
              onClick = {
                val url = textFieldValue.text
                if (isHostApproved(url)) {
                  validateAndAddSkill(url)
                } else {
                  showDisclaimerDialog = true
                }
              }
            ) {
              Text(stringResource(R.string.add))
            }
          }
        }
      }
    }
  }

  if (showDisclaimerDialog) {
    AddSkillDisclaimerDialog(
      onDismiss = { showDisclaimerDialog = false },
      onConfirm = {
        showDisclaimerDialog = false
        validateAndAddSkill(textFieldValue.text)
      },
    )
  }
}

fun isHostApproved(url: String): Boolean {
  return try {
    val uri = URI(url).normalize()
    val parsedHost = uri.host?.lowercase() ?: return false

    // Check if the parsed host matches any host in our allowlist
    APPROVED_SKILL_HOSTS.any { allowed -> parsedHost == allowed.lowercase() }
  } catch (e: Exception) {
    false // Invalid URI syntax
  }
}
