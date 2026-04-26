package com.server.edge.gallery.ui.home

import android.app.UiModeManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Policy
import androidx.compose.material.icons.outlined.SettingsBrightness
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.server.edge.gallery.BuildConfig
import com.server.edge.gallery.proto.Theme
import com.server.edge.gallery.ui.common.tos.AppTosDialog
import com.server.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.server.edge.gallery.ui.theme.ThemeSettings
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.min

private val THEME_OPTIONS = listOf(
    Triple(Theme.THEME_LIGHT, "Light", Icons.Outlined.LightMode),
    Triple(Theme.THEME_AUTO, "Auto", Icons.Outlined.SettingsBrightness),
    Triple(Theme.THEME_DARK, "Dark", Icons.Outlined.DarkMode),
)

/**
 * Full-page Settings screen (4th bottom nav tab).
 * Contains: Theme switcher, HuggingFace token, ToS, version footer.
 * Tunnel config has been moved to the Server tab.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    modelManagerViewModel: ModelManagerViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    var selectedTheme by remember { mutableStateOf(ThemeSettings.themeOverride.value) }

    val dateFormatter = remember {
        DateTimeFormatter.ofPattern("yyyy-MM-dd")
            .withZone(ZoneId.systemDefault())
            .withLocale(Locale.getDefault())
    }

    // HF token state
    var hfToken by remember { mutableStateOf(modelManagerViewModel.getTokenStatusAndData().data) }
    var customHfToken by remember { mutableStateOf("") }
    var hfFieldFocused by remember { mutableStateOf(false) }
    var tokenSaved by remember { mutableStateOf(false) }

    var showTos by remember { mutableStateOf(false) }

    val handleSaveToken = {
        modelManagerViewModel.saveAccessToken(
            accessToken = customHfToken,
            refreshToken = "",
            expiresAt = System.currentTimeMillis() + 1000L * 60 * 60 * 24 * 365 * 10,
        )
        hfToken = modelManagerViewModel.getTokenStatusAndData().data
        customHfToken = ""
        tokenSaved = true
        focusManager.clearFocus()
        Toast.makeText(context, "Token saved — model downloads updated", Toast.LENGTH_SHORT).show()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        // ── Header ──────────────────────────────────────────────────────────
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp),
        ) {
            Text(
                "Settings",
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Appearance, access token, and about",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ── Theme Card ───────────────────────────────────────────────────────
        SettingsCard(
            icon = Icons.Outlined.SettingsBrightness,
            title = "Theme",
        ) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                THEME_OPTIONS.forEachIndexed { index, (theme, label, icon) ->
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = THEME_OPTIONS.size),
                        selected = selectedTheme == theme,
                        onClick = {
                            selectedTheme = theme
                            ThemeSettings.themeOverride.value = theme
                            modelManagerViewModel.saveThemeOverride(theme)
                            val uiModeManager =
                                context.applicationContext.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
                            when (theme) {
                                Theme.THEME_LIGHT -> uiModeManager.setApplicationNightMode(UiModeManager.MODE_NIGHT_NO)
                                Theme.THEME_DARK -> uiModeManager.setApplicationNightMode(UiModeManager.MODE_NIGHT_YES)
                                else -> uiModeManager.setApplicationNightMode(UiModeManager.MODE_NIGHT_AUTO)
                            }
                        },
                        icon = {
                            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                        label = { Text(label) },
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── HuggingFace Token Card ───────────────────────────────────────────
        SettingsCard(
            icon = Icons.Outlined.Key,
            title = "HuggingFace Access Token",
        ) {
            val curHfToken = hfToken
            if (curHfToken != null && curHfToken.accessToken.isNotEmpty()) {
                // Show existing token preview
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .padding(12.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            curHfToken.accessToken.substring(0, min(20, curHfToken.accessToken.length)) + "…",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                        )
                        Text(
                            "Expires ${dateFormatter.format(Instant.ofEpochMilli(curHfToken.expiresAtMs))}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        Icons.Outlined.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = {
                        modelManagerViewModel.clearAccessToken()
                        hfToken = null
                        tokenSaved = false
                    },
                ) {
                    Text("Clear token", color = MaterialTheme.colorScheme.error)
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            }

            Text(
                if (curHfToken != null) "Replace token" else "Enter token for gated model downloads",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))

            // Token input
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val borderColor by animateColorAsState(
                    targetValue = if (hfFieldFocused) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline,
                    animationSpec = tween(200),
                    label = "border",
                )
                BasicTextField(
                    value = customHfToken,
                    onValueChange = {
                        customHfToken = it
                        tokenSaved = false
                    },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (customHfToken.isNotEmpty()) handleSaveToken() }),
                    textStyle = TextStyle(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.sp,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .weight(1f)
                        .onFocusChanged { hfFieldFocused = it.isFocused },
                ) { inner ->
                    Box(
                        modifier = Modifier
                            .border(width = if (hfFieldFocused) 2.dp else 1.dp, color = borderColor, shape = RoundedCornerShape(10.dp))
                            .height(46.dp)
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (customHfToken.isEmpty()) {
                            Text(
                                "hf_xxxxxxxxxxxxxxxxxx",
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                fontSize = 13.sp,
                            )
                        }
                        inner()
                    }
                }
                AnimatedContent(targetState = customHfToken.isNotEmpty(), label = "save_btn") { canSave ->
                    if (canSave) {
                        Button(
                            onClick = { handleSaveToken() },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                            ),
                        ) {
                            Text("Save")
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── Terms of Service Card ────────────────────────────────────────────
        SettingsCard(
            icon = Icons.Outlined.Policy,
            title = "Legal",
        ) {
            TextButton(
                onClick = { showTos = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text("View App Terms of Service", modifier = Modifier.weight(1f))
            }
            HorizontalDivider()
            TextButton(
                onClick = {
                    // Gemma terms link
                    val intent = android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://ai.google.dev/gemma/terms")
                    )
                    context.startActivity(intent)
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text("Gemma Terms of Service", modifier = Modifier.weight(1f))
            }
        }

        Spacer(Modifier.height(24.dp))

        // ── Version footer ───────────────────────────────────────────────────
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp),
        ) {
            Icon(
                Icons.Outlined.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Edge Gallery",
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
            Text(
                "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
        }
    }

    if (showTos) {
        AppTosDialog(onTosAccepted = { showTos = false }, viewingMode = true)
    }
}

@Composable
private fun SettingsCard(
    icon: ImageVector,
    title: String,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 14.dp),
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                )
            }
            content()
        }
    }
}
