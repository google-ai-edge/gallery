/*
 * Copyright 2025 Google LLC
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

package com.google.ai.edge.gallery.ui.home

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MultiChoiceSegmentedButtonRow
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.navigation.NavController
import com.google.ai.edge.gallery.R // Added import for R class
import com.google.ai.edge.gallery.BuildConfig
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.gallery.ui.navigation.PersonaManagementDestination // For the route
import com.google.ai.edge.gallery.ui.navigation.UserProfileDestination // For the route
import com.google.ai.edge.gallery.ui.theme.THEME_AUTO
import com.google.ai.edge.gallery.ui.theme.THEME_DARK
import com.google.ai.edge.gallery.ui.theme.THEME_LIGHT
import com.google.ai.edge.gallery.ui.theme.ThemeSettings
import com.google.ai.edge.gallery.ui.theme.labelSmallNarrow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.min

private val THEME_OPTIONS = listOf(THEME_AUTO, THEME_LIGHT, THEME_DARK)

@Composable
fun SettingsDialog(
  curThemeOverride: String,
  modelManagerViewModel: ModelManagerViewModel,
  navController: NavController, // Add NavController
  onDismissed: () -> Unit,
) {
  var selectedTheme by remember { mutableStateOf(curThemeOverride) }
  var hfToken by remember { mutableStateOf(modelManagerViewModel.getTokenStatusAndData().data) }
  val dateFormatter = remember {
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())
      .withLocale(Locale.getDefault())
  }
  var customHfToken by remember { mutableStateOf("") }
  var isFocused by remember { mutableStateOf(false) }
  val focusRequester = remember { FocusRequester() }
  val interactionSource = remember { MutableInteractionSource() }

  Dialog(onDismissRequest = onDismissed) {
    val focusManager = LocalFocusManager.current
    Card(
      modifier = Modifier
        .fillMaxWidth()
        .clickable(
          interactionSource = interactionSource, indication = null // Disable the ripple effect
        ) {
          focusManager.clearFocus()
        }, shape = RoundedCornerShape(16.dp)
    ) {
      Column(
        modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)
      ) {
        // Dialog title and subtitle.
        Column {
          Text(
            "Settings",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(bottom = 8.dp)
          )
          // Subtitle.
          Text(
            "App version: ${BuildConfig.VERSION_NAME}",
            style = labelSmallNarrow,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.offset(y = (-6).dp)
          )
        }

        Column(
          modifier = Modifier
            .verticalScroll(rememberScrollState())
            .weight(1f, fill = false),
          verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
          // Theme switcher.
          Column(
            modifier = Modifier.fillMaxWidth()
          ) {
            Text(
              "Theme",
              style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
            )
            MultiChoiceSegmentedButtonRow {
              THEME_OPTIONS.forEachIndexed { index, label ->
                SegmentedButton(shape = SegmentedButtonDefaults.itemShape(
                  index = index, count = THEME_OPTIONS.size
                ), onCheckedChange = {
                  selectedTheme = label

                  // Update theme settings.
                  // This will update app's theme.
                  ThemeSettings.themeOverride.value = label

                  // Save to data store.
                  modelManagerViewModel.saveThemeOverride(label)
                }, checked = label == selectedTheme, label = { Text(label) })
              }
            }
          }

          // HF Token management.
          Column(
            modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)
          ) {
            Text(
              "HuggingFace access token",
              style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
            )
            // Show the start of the token.
            val curHfToken = hfToken
            if (curHfToken != null) {
              Text(
                curHfToken.accessToken.substring(0, min(16, curHfToken.accessToken.length)) + "...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
              Text(
                "Expired at: ${dateFormatter.format(Instant.ofEpochMilli(curHfToken.expiresAtMs))}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
            } else {
              Text(
                "Not available",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
              Text(
                "The token will be automatically retrieved when a gated model is downloaded",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
              OutlinedButton(
                onClick = {
                  modelManagerViewModel.clearAccessToken()
                  hfToken = null
                }, enabled = curHfToken != null
              ) {
                Text("Clear")
              }
              BasicTextField(
                value = customHfToken,
                singleLine = true,
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(top = 4.dp)
                  .focusRequester(focusRequester)
                  .onFocusChanged {
                    isFocused = it.isFocused
                  },
                onValueChange = {
                  customHfToken = it
                },
                textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
              ) { innerTextField ->
                Box(
                  modifier = Modifier
                    .border(
                      width = if (isFocused) 2.dp else 1.dp,
                      color = if (isFocused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                      shape = CircleShape,
                    )
                    .height(40.dp), contentAlignment = Alignment.CenterStart
                ) {
                  Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                      modifier = Modifier
                        .padding(start = 16.dp)
                        .weight(1f)
                    ) {
                      if (customHfToken.isEmpty()) {
                        Text(
                          "Enter token manually",
                          color = MaterialTheme.colorScheme.onSurfaceVariant,
                          style = MaterialTheme.typography.bodySmall
                        )
                      }
                      innerTextField()
                    }
                    if (customHfToken.isNotEmpty()) {
                      IconButton(
                        modifier = Modifier.offset(x = 1.dp),
                        onClick = {
                          modelManagerViewModel.saveAccessToken(
                            accessToken = customHfToken,
                            refreshToken = "",
                            expiresAt = System.currentTimeMillis() + 1000L * 60 * 60 * 24 * 365 * 10,
                          )
                          hfToken = modelManagerViewModel.getTokenStatusAndData().data
                        }) {
                        Icon(Icons.Rounded.CheckCircle, contentDescription = "")
                      }
                    }
                  }
                }
              }
            }
          }

          // Personal Profile Section
          Column(
            modifier = Modifier.fillMaxWidth()
          ) {
            Text(
              stringResource(R.string.settings_personal_profile_title),
              style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
              modifier = Modifier.padding(bottom = 8.dp)
            )
            OutlinedButton(
              onClick = {
                navController.navigate(UserProfileDestination.route)
                onDismissed() // Optionally dismiss settings dialog after navigation
              },
              modifier = Modifier.fillMaxWidth()
            ) {
              Text(stringResource(R.string.settings_edit_profile_button))
            }
          }

          // Persona Management Section
          Column(
            modifier = Modifier.fillMaxWidth()
          ) {
            Text(
              stringResource(R.string.settings_persona_management_title),
              style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
              modifier = Modifier.padding(top = 16.dp, bottom = 8.dp) // Add top padding
            )
            OutlinedButton(
              onClick = {
                navController.navigate(PersonaManagementDestination.route)
                onDismissed() // Optionally dismiss settings dialog
              },
              modifier = Modifier.fillMaxWidth()
            ) {
              Text(stringResource(R.string.settings_manage_personas_button))
            }
          }
        }


        // Button row.
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
          horizontalArrangement = Arrangement.End,
        ) {
          // Close button
          Button(
            onClick = {
              onDismissed()
            },
          ) {
            Text("Close")
          }
        }
      }
    }
  }
}
