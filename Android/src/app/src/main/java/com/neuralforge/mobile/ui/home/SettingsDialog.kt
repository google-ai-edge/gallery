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

package com.neuralforge.mobile.ui.home

import com.google.android.gms.oss.licenses.OssLicensesMenuActivity
import android.app.UiModeManager
import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.neuralforge.mobile.BuildConfig
import com.neuralforge.mobile.R
import com.neuralforge.mobile.proto.Theme
import com.neuralforge.mobile.ui.common.tos.TosDialog
import com.neuralforge.mobile.ui.modelmanager.ModelManagerViewModel
import com.neuralforge.mobile.ui.theme.ThemeSettings
import com.neuralforge.mobile.ui.theme.labelSmallNarrow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.min

private val THEME_OPTIONS = listOf(Theme.THEME_AUTO, Theme.THEME_LIGHT, Theme.THEME_DARK)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDialog(
  curThemeOverride: Theme,
  modelManagerViewModel: ModelManagerViewModel,
  onDismissed: () -> Unit,
) {
  var selectedTheme by remember { mutableStateOf(curThemeOverride) }
  var hfToken by remember { mutableStateOf(modelManagerViewModel.getTokenStatusAndData().data) }
  val dateFormatter = remember {
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
      .withZone(ZoneId.systemDefault())
      .withLocale(Locale.getDefault())
  }
  var customHfToken by remember { mutableStateOf("") }
  var isFocused by remember { mutableStateOf(false) }
  val focusRequester = remember { FocusRequester() }
  val interactionSource = remember { MutableInteractionSource() }
  var showTos by remember { mutableStateOf(false) }

  Dialog(onDismissRequest = onDismissed) {
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
        // Dialog title and subtitle.
        Column {
          Text(
            "Settings",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(bottom = 8.dp),
          )
          // Subtitle.
          Text(
            "App version: ${BuildConfig.VERSION_NAME}",
            style = labelSmallNarrow,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.offset(y = (-6).dp),
          )
        }

        Column(
          modifier = Modifier.verticalScroll(rememberScrollState()).weight(1f, fill = false),
          verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
          val context = LocalContext.current
          // Theme switcher.
          Column(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
            Text(
              "Theme",
              style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
            )
            MultiChoiceSegmentedButtonRow {
              THEME_OPTIONS.forEachIndexed { index, theme ->
                SegmentedButton(
                  shape =
                    SegmentedButtonDefaults.itemShape(index = index, count = THEME_OPTIONS.size),
                  onCheckedChange = {
                    selectedTheme = theme

                    // Update theme settings.
                    // This will update app's theme.
                    ThemeSettings.themeOverride.value = theme

                    // Save to data store.
                    modelManagerViewModel.saveThemeOverride(theme)

                    // Update ui mode.
                    //
                    // This is necessary to make other Activities launched from MainActivity to have
                    // the correct theme.
                    val uiModeManager =
                      context.applicationContext.getSystemService(Context.UI_MODE_SERVICE)
                        as UiModeManager
                    if (theme == Theme.THEME_AUTO) {
                      uiModeManager.setApplicationNightMode(UiModeManager.MODE_NIGHT_AUTO)
                    } else if (theme == Theme.THEME_LIGHT) {
                      uiModeManager.setApplicationNightMode(UiModeManager.MODE_NIGHT_NO)
                    } else {
                      uiModeManager.setApplicationNightMode(UiModeManager.MODE_NIGHT_YES)
                    }
                  },
                  checked = theme == selectedTheme,
                  label = { Text(themeLabel(theme)) },
                )
              }
            }
          }

          // HF Token management.
          Column(
            modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
            verticalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            Text(
              "HuggingFace access token",
              style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
            )
            // Show the start of the token.
            val curHfToken = hfToken
            if (curHfToken != null && curHfToken.accessToken.isNotEmpty()) {
              Text(
                curHfToken.accessToken.substring(0, min(16, curHfToken.accessToken.length)) + "...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              Text(
                "Expires at: ${dateFormatter.format(Instant.ofEpochMilli(curHfToken.expiresAtMs))}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            } else {
              Text(
                "Not available",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              Text(
                "The token will be automatically retrieved when a gated model is downloaded",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
              OutlinedButton(
                onClick = {
                  modelManagerViewModel.clearAccessToken()
                  hfToken = null
                },
                enabled = curHfToken != null,
              ) {
                Text("Clear")
              }
              val handleSaveToken = {
                modelManagerViewModel.saveAccessToken(
                  accessToken = customHfToken,
                  refreshToken = "",
                  expiresAt = System.currentTimeMillis() + 1000L * 60 * 60 * 24 * 365 * 10,
                )
                hfToken = modelManagerViewModel.getTokenStatusAndData().data
                focusManager.clearFocus()
              }
              BasicTextField(
                value = customHfToken,
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { handleSaveToken() }),
                modifier =
                  Modifier.fillMaxWidth()
                    .padding(top = 4.dp)
                    .focusRequester(focusRequester)
                    .onFocusChanged { isFocused = it.isFocused },
                onValueChange = { customHfToken = it },
                textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
              ) { innerTextField ->
                Box(
                  modifier =
                    Modifier.border(
                        width = if (isFocused) 2.dp else 1.dp,
                        color =
                          if (isFocused) MaterialTheme.colorScheme.primary
                          else MaterialTheme.colorScheme.outline,
                        shape = CircleShape,
                      )
                      .height(40.dp),
                  contentAlignment = Alignment.CenterStart,
                ) {
                  Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.padding(start = 16.dp).weight(1f)) {
                      if (customHfToken.isEmpty()) {
                        Text(
                          "Enter token manually",
                          color = MaterialTheme.colorScheme.onSurfaceVariant,
                          style = MaterialTheme.typography.bodySmall,
                        )
                      }
                      innerTextField()
                    }
                    if (customHfToken.isNotEmpty()) {
                      IconButton(modifier = Modifier.offset(x = 1.dp), onClick = handleSaveToken) {
                        Icon(
                          Icons.Rounded.CheckCircle,
                          contentDescription = stringResource(R.string.cd_done_icon),
                        )
                      }
                    }
                  }
                }
              }
            }
          }

          // Third party licenses.
          Column(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
            Text(
              "Third-party libraries",
              style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
            )
            OutlinedButton(
              onClick = {
                // Create an Intent to launch a license viewer that displays a list of
                // third-party library names. Clicking a name will show its license content.
                val intent = Intent(context, OssLicensesMenuActivity::class.java)
                context.startActivity(intent)
              }
            ) {
              Text("View licenses")
            }
          }

          // Tos
          Column(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
            Text(
              stringResource(R.string.settings_dialog_tos_title),
              style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
            )
            OutlinedButton(onClick = { showTos = true }) { Text("View Terms of Services") }
          }
        }

        // Button row.
        Row(
          modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
          horizontalArrangement = Arrangement.End,
        ) {
          // Close button
          Button(onClick = { onDismissed() }) { Text("Close") }
        }
      }
    }
  }

  if (showTos) {
    TosDialog(onTosAccepted = { showTos = false }, viewingMode = true)
  }
}

private fun themeLabel(theme: Theme): String {
  return when (theme) {
    Theme.THEME_AUTO -> "Auto"
    Theme.THEME_LIGHT -> "Light"
    Theme.THEME_DARK -> "Dark"
    else -> "Unknown"
  }
}
