package com.appswithlove.ai.ui.home

import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
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
import com.appswithlove.ai.BuildConfig
import com.appswithlove.ai.R
import com.appswithlove.ai.proto.Theme
import com.appswithlove.ai.ui.common.ClickableLink
import com.appswithlove.ai.ui.common.tos.AppTosDialog
import com.appswithlove.ai.ui.modelmanager.ModelManagerViewModel
import com.appswithlove.ai.ui.theme.LocaleSettings
import com.appswithlove.ai.ui.theme.ThemeSettings
import com.appswithlove.ai.ui.theme.labelSmallNarrow
import com.google.android.gms.oss.licenses.OssLicensesMenuActivity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.min

private val THEME_OPTIONS = listOf(Theme.THEME_AUTO, Theme.THEME_LIGHT, Theme.THEME_DARK)

private data class SettingsLanguageOption(val tag: String, val label: String)

private val LANGUAGE_OPTIONS = listOf(
  SettingsLanguageOption("de", "DE"),
  SettingsLanguageOption("fr", "FR"),
  SettingsLanguageOption("it", "IT"),
  SettingsLanguageOption("en", "EN"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
  modelManagerViewModel: ModelManagerViewModel,
  modifier: Modifier = Modifier,
) {
  val curThemeOverride = modelManagerViewModel.readThemeOverride()
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
  val focusManager = LocalFocusManager.current
  var showTos by remember { mutableStateOf(false) }
  var selectedLanguageTag by remember { mutableStateOf(LocaleSettings.languageTag.value) }
  val context = LocalContext.current

  Column(
    modifier = modifier
      .fillMaxSize()
      .background(MaterialTheme.colorScheme.surfaceContainer)
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 24.dp),
    verticalArrangement = Arrangement.spacedBy(24.dp),
  ) {
    Spacer(modifier = Modifier.height(0.dp))

    Column {
      Text(
        stringResource(R.string.settings_title),
        style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onSurface,
      )
      Text(
        stringResource(R.string.settings_app_version, BuildConfig.VERSION_NAME),
        style = labelSmallNarrow,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
      )
    }

    // Theme switcher
    Column(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
      Text(
        stringResource(R.string.settings_theme),
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
      )
      MultiChoiceSegmentedButtonRow {
        THEME_OPTIONS.forEachIndexed { index, theme ->
          SegmentedButton(
            shape = SegmentedButtonDefaults.itemShape(index = index, count = THEME_OPTIONS.size),
            onCheckedChange = {
              selectedTheme = theme
              ThemeSettings.themeOverride.value = theme
              modelManagerViewModel.saveThemeOverride(theme)
              val uiModeManager =
                context.applicationContext.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
              when (theme) {
                Theme.THEME_AUTO -> uiModeManager.setApplicationNightMode(UiModeManager.MODE_NIGHT_AUTO)
                Theme.THEME_LIGHT -> uiModeManager.setApplicationNightMode(UiModeManager.MODE_NIGHT_NO)
                else -> uiModeManager.setApplicationNightMode(UiModeManager.MODE_NIGHT_YES)
              }
            },
            checked = theme == selectedTheme,
            label = { Text(themeLabel(theme)) },
          )
        }
      }
    }

    // Language switcher
    Column(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
      Text(
        stringResource(R.string.settings_language),
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
      )
      MultiChoiceSegmentedButtonRow {
        LANGUAGE_OPTIONS.forEachIndexed { index, option ->
          SegmentedButton(
            shape = SegmentedButtonDefaults.itemShape(index = index, count = LANGUAGE_OPTIONS.size),
            onCheckedChange = {
              selectedLanguageTag = option.tag
              LocaleSettings.languageTag.value = option.tag
              modelManagerViewModel.saveLanguageTag(option.tag)
            },
            checked = option.tag == selectedLanguageTag,
            label = { Text(option.label) },
          )
        }
      }
    }

    // HF Token management
    Column(
      modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      Text(
        stringResource(R.string.settings_hf_token_title),
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
      )
      val curHfToken = hfToken
      if (curHfToken != null && curHfToken.accessToken.isNotEmpty()) {
        Text(
          curHfToken.accessToken.substring(0, min(16, curHfToken.accessToken.length)) + "...",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
          stringResource(
            R.string.settings_hf_expires_at,
            dateFormatter.format(Instant.ofEpochMilli(curHfToken.expiresAtMs)),
          ),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      } else {
        Text(
          stringResource(R.string.settings_hf_not_available),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
          stringResource(R.string.settings_hf_auto_retrieve),
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
          Text(stringResource(R.string.clear))
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
          modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            .focusRequester(focusRequester)
            .onFocusChanged { isFocused = it.isFocused },
          onValueChange = { customHfToken = it },
          textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface),
          cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
        ) { innerTextField ->
          Box(
            modifier = Modifier.border(
              width = if (isFocused) 2.dp else 1.dp,
              color = if (isFocused) MaterialTheme.colorScheme.primary
              else MaterialTheme.colorScheme.outline,
              shape = CircleShape,
            ).height(40.dp),
            contentAlignment = Alignment.CenterStart,
          ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Box(modifier = Modifier.padding(start = 16.dp).weight(1f)) {
                if (customHfToken.isEmpty()) {
                  Text(
                    stringResource(R.string.settings_hf_enter_manually),
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

    // Third-party licenses
    Column(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
      Text(
        stringResource(R.string.settings_third_party_libs),
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
      )
      OutlinedButton(
        onClick = {
          val intent = Intent(context, OssLicensesMenuActivity::class.java)
          context.startActivity(intent)
        }
      ) {
        Text(stringResource(R.string.settings_view_licenses))
      }
    }

    // ToS
    Column(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
      Text(
        stringResource(R.string.settings_dialog_tos_title),
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
      )
      OutlinedButton(onClick = { showTos = true }) {
        Text(stringResource(R.string.settings_dialog_view_app_terms_of_service))
      }
      ClickableLink(
        url = "https://ai.google.dev/gemma/terms",
        linkText = stringResource(R.string.tos_dialog_title_gemma),
        modifier = Modifier.padding(top = 4.dp),
      )
      ClickableLink(
        url = "https://ai.google.dev/gemma/prohibited_use_policy",
        linkText = stringResource(R.string.settings_dialog_gemma_prohibited_use_policy),
        modifier = Modifier.padding(top = 8.dp),
      )
    }

    Spacer(modifier = Modifier.height(16.dp))
  }

  if (showTos) {
    AppTosDialog(onTosAccepted = { showTos = false }, viewingMode = true)
  }
}
