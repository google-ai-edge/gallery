package com.server.edge.gallery.ui.server

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.border
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.server.edge.gallery.openai.OpenAiServerService
import com.server.edge.gallery.openai.OpenAiServerState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    LaunchedEffect(context) {
        OpenAiServerState.loadTunnelPreference(context)
    }
    val isRunning by OpenAiServerState.isRunning.collectAsState()
    val localUrl by OpenAiServerState.localUrl.collectAsState()
    val publicUrl by OpenAiServerState.publicUrl.collectAsState()
    val isTunnelEnabled by OpenAiServerState.isTunnelEnabled.collectAsState()
    val tunnelProvider by OpenAiServerState.tunnelProvider.collectAsState()
    var enableTunnel by remember(isTunnelEnabled) { mutableStateOf(isTunnelEnabled) }
    var selectedProvider by remember(tunnelProvider) { mutableStateOf(tunnelProvider) }

    var cfToken by remember { mutableStateOf(OpenAiServerState.cloudflareTunnelToken(context)) }
    var cfUrl by remember { mutableStateOf(OpenAiServerState.cloudflarePublicUrl(context)) }
    var ngrokToken by remember { mutableStateOf(OpenAiServerState.ngrokAuthToken(context)) }
    var ngrokDomain by remember { mutableStateOf(OpenAiServerState.ngrokDomain(context)) }

    var focusedField by remember { mutableStateOf<String?>(null) }

    // Health test state
    var localTestInProgress by remember { mutableStateOf(false) }
    var localTestResult by remember { mutableStateOf<String?>(null) }
    var localTestSuccess by remember { mutableStateOf(false) }

    var externalTestInProgress by remember { mutableStateOf(false) }
    var externalTestResult by remember { mutableStateOf<String?>(null) }
    var externalTestSuccess by remember { mutableStateOf(false) }

    val contentAlpha by animateFloatAsState(
        targetValue = if (isRunning) 1f else 0.5f,
        animationSpec = tween(300),
        label = "contentAlpha"
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Header
        Text(
            text = "API Server",
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "Expose your on-device models as an OpenAI-compatible API server.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Enable / Disable Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (isRunning)
                    MaterialTheme.colorScheme.primaryContainer
                else
                    MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
            shape = RoundedCornerShape(16.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Status indicator dot
                val dotColor by animateColorAsState(
                    targetValue = if (isRunning)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.outline,
                    animationSpec = tween(300),
                    label = "dotColor",
                )
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isRunning) "Server Running" else "Server Stopped",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (isRunning)
                            MaterialTheme.colorScheme.onPrimaryContainer
                        else
                            MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = if (isRunning) "Accepting API requests" else "Tap the toggle to start",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isRunning)
                            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = isRunning,
                    onCheckedChange = { checked ->
                        if (checked) {
                            requestIgnoreBatteryOptimizations(context)
                            OpenAiServerState.persistTunnelEnabled(context, enableTunnel)
                            OpenAiServerService.startService(context, useTunnel = enableTunnel)
                        } else {
                            OpenAiServerService.stopService(context)
                        }
                    },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                )
            }
        }

        // Tunnel configuration (only when server is off)
        AnimatedVisibility(
            visible = !isRunning,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                ),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // Tunnel enable toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Outlined.Public,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Internet Tunnel",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                text = "Expose server with a public URL",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = enableTunnel,
                            onCheckedChange = {
                                enableTunnel = it
                                OpenAiServerState.persistTunnelEnabled(context, it)
                            },
                        )
                    }

                    // Tunnel provider selection
                    AnimatedVisibility(
                        visible = enableTunnel,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically(),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "Tunnel Provider",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            val providers = listOf(
                                OpenAiServerState.TUNNEL_PROVIDER_CLOUDFLARE to "Cloudflare",
                                OpenAiServerState.TUNNEL_PROVIDER_NGROK to "ngrok",
                            )
                            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                                providers.forEachIndexed { index, (value, label) ->
                                    SegmentedButton(
                                        selected = selectedProvider == value,
                                        onClick = {
                                            selectedProvider = value
                                            OpenAiServerState.persistTunnelProvider(context, value)
                                        },
                                        shape = SegmentedButtonDefaults.itemShape(
                                            index = index,
                                            count = providers.size,
                                        ),
                                    ) {
                                        Text(label)
                                    }
                                }
                            }
                            val isCf = selectedProvider == OpenAiServerState.TUNNEL_PROVIDER_CLOUDFLARE
                            Text(
                                text = if (isCf)
                                    "Cloudflare works out of the box. For a stable URL, configure a named tunnel."
                                else
                                    "ngrok requires an authtoken and optionally a reserved domain.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(4.dp))
                            AnimatedContent(targetState = isCf, label = "tunnel_inputs") { showCf ->
                                if (showCf) {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        ServerScreenTextField(
                                            value = cfToken,
                                            onValueChange = { cfToken = it },
                                            placeholder = "Cloudflare tunnel token (optional)",
                                            isFocused = focusedField == "cfToken",
                                            onFocusChanged = { if (it) focusedField = "cfToken" else if (focusedField == "cfToken") focusedField = null }
                                        )
                                        ServerScreenTextField(
                                            value = cfUrl,
                                            onValueChange = { cfUrl = it },
                                            placeholder = "Cloudflare public URL (e.g. https://api.yourdomain.com)",
                                            isFocused = focusedField == "cfUrl",
                                            onFocusChanged = { if (it) focusedField = "cfUrl" else if (focusedField == "cfUrl") focusedField = null }
                                        )
                                        Button(
                                            onClick = {
                                                OpenAiServerState.persistCloudflareTunnelConfig(context, cfToken, cfUrl)
                                                Toast.makeText(context, "Cloudflare config saved", Toast.LENGTH_SHORT).show()
                                            },
                                            modifier = Modifier.align(Alignment.End)
                                        ) {
                                            Text("Save Cloudflare")
                                        }
                                    }
                                } else {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        ServerScreenTextField(
                                            value = ngrokToken,
                                            onValueChange = { ngrokToken = it },
                                            placeholder = "ngrok authtoken",
                                            isFocused = focusedField == "ngrokToken",
                                            onFocusChanged = { if (it) focusedField = "ngrokToken" else if (focusedField == "ngrokToken") focusedField = null }
                                        )
                                        ServerScreenTextField(
                                            value = ngrokDomain,
                                            onValueChange = { ngrokDomain = it },
                                            placeholder = "ngrok reserved domain (optional)",
                                            isFocused = focusedField == "ngrokDomain",
                                            onFocusChanged = { if (it) focusedField = "ngrokDomain" else if (focusedField == "ngrokDomain") focusedField = null }
                                        )
                                        Button(
                                            onClick = {
                                                OpenAiServerState.persistNgrokConfig(context, ngrokToken, ngrokDomain)
                                                Toast.makeText(context, "ngrok config saved", Toast.LENGTH_SHORT).show()
                                            },
                                            modifier = Modifier.align(Alignment.End)
                                        ) {
                                            Text("Save ngrok")
                                        }
                                    }
                                }
                            }

                        }
                    }
                }
            }
        }

        // Endpoints Section
        AnimatedVisibility(
            visible = isRunning,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Endpoints",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 4.dp),
                )

                // Local endpoint
                val safeLocalUrl = localUrl
                if (safeLocalUrl != null) {
                    EndpointCard(
                        icon = Icons.Outlined.Wifi,
                        label = "Local Network",
                        url = safeLocalUrl,
                        description = "Accessible from devices on same Wi-Fi",
                        context = context,
                    )
                }

                // Public endpoint
                val providerLabel = if (selectedProvider == OpenAiServerState.TUNNEL_PROVIDER_NGROK) "ngrok" else "Cloudflare"
                val safePublicUrl = publicUrl
                if (safePublicUrl != null) {
                    EndpointCard(
                        icon = Icons.Outlined.Cloud,
                        label = "Internet ($providerLabel Tunnel)",
                        url = safePublicUrl,
                        description = "Accessible from anywhere on the internet",
                        context = context,
                    )
                } else if (isRunning && isTunnelEnabled) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                        ),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Outlined.Cloud,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "Establishing $providerLabel tunnel…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                // Health test buttons
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Health Tests",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )

                // Test local health
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            val url = safeLocalUrl
                            if (url != null) {
                                localTestInProgress = true
                                localTestResult = null
                                coroutineScope.launch {
                                    val result = testHealthEndpoint(url.trimEnd('/') + "/health")
                                    localTestSuccess = result.first
                                    localTestResult = result.second
                                    localTestInProgress = false
                                }
                            }
                        },
                        enabled = !localTestInProgress && safeLocalUrl != null,
                        modifier = Modifier.weight(1f),
                    ) {
                        if (localTestInProgress) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                        } else {
                            Icon(Icons.Outlined.Lan, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(if (localTestInProgress) "Testing…" else "Test Local")
                    }

                    // Test external health
                    OutlinedButton(
                        onClick = {
                            val url = safePublicUrl
                            if (url != null) {
                                externalTestInProgress = true
                                externalTestResult = null
                                coroutineScope.launch {
                                    val result = testHealthEndpoint(url.trimEnd('/') + "/health")
                                    externalTestSuccess = result.first
                                    externalTestResult = result.second
                                    externalTestInProgress = false
                                }
                            }
                        },
                        enabled = !externalTestInProgress && safePublicUrl != null,
                        modifier = Modifier.weight(1f),
                    ) {
                        if (externalTestInProgress) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                        } else {
                            Icon(Icons.Outlined.Public, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(if (externalTestInProgress) "Testing…" else "Test External")
                    }
                }

                // Health test results
                localTestResult?.let { msg ->
                    HealthTestResultCard(success = localTestSuccess, label = "Local", message = msg)
                }
                externalTestResult?.let { msg ->
                    HealthTestResultCard(success = externalTestSuccess, label = "External", message = msg)
                }
            }
        }

        // Usage Examples Section
        AnimatedVisibility(
            visible = isRunning,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Usage Examples",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )

                val baseUrl = publicUrl ?: localUrl ?: "http://localhost:8080"

                // List models
                CodeExampleCard(
                    title = "List Available Models",
                    language = "curl",
                    code = "curl $baseUrl/v1/models",
                    context = context,
                )

                // Chat completion
                CodeExampleCard(
                    title = "Chat Completion",
                    language = "curl",
                    code = """curl $baseUrl/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "YOUR_MODEL_NAME",
    "messages": [
      {"role": "user", "content": "Hello!"}
    ]
  }'""",
                    context = context,
                )

                // Streaming
                CodeExampleCard(
                    title = "Streaming Response",
                    language = "curl",
                    code = """curl $baseUrl/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "YOUR_MODEL_NAME",
    "messages": [
      {"role": "user", "content": "Hello!"}
    ],
    "stream": true
  }'""",
                    context = context,
                )

                // Python
                CodeExampleCard(
                    title = "Python (OpenAI SDK)",
                    language = "python",
                    code = """from openai import OpenAI

client = OpenAI(
    base_url="$baseUrl/v1",
    api_key="not-needed"
)

response = client.chat.completions.create(
    model="YOUR_MODEL_NAME",
    messages=[
        {"role": "user", "content": "Hello!"}
    ]
)
print(response.choices[0].message.content)""",
                    context = context,
                )

                // Health check
                CodeExampleCard(
                    title = "Health Check",
                    language = "curl",
                    code = "curl $baseUrl/health",
                    context = context,
                )

                Spacer(modifier = Modifier.height(80.dp))
            }
        }

        // Info when server is off
        AnimatedVisibility(
            visible = !isRunning,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
                shape = RoundedCornerShape(16.dp),
            ) {
                Row(
                    modifier = Modifier.padding(20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        Icons.Outlined.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "About API Server",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "The API server exposes your downloaded on-device models via an OpenAI-compatible REST API. " +
                                "You can use it with any tool that supports the OpenAI API format — curl, Python SDK, LangChain, etc.\n\n" +
                                "• Local endpoint uses your phone's Wi-Fi/LAN IP for access from other devices\n" +
                                "• localhost only works on the same device that is running Edge Gallery\n" +
                                "• Internet tunnel (via Cloudflare) makes it accessible from anywhere\n" +
                                "• Models must be downloaded first from the Chat tab",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 18.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EndpointCard(
    icon: ImageVector,
    label: String,
    url: String,
    description: String,
    context: Context,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                    .clickable { copyToClipboard(context, url) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = url,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    Icons.Outlined.ContentCopy,
                    contentDescription = "Copy URL",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CodeExampleCard(
    title: String,
    language: String,
    code: String,
    context: Context,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                IconButton(
                    onClick = { copyToClipboard(context, code) },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        Icons.Outlined.ContentCopy,
                        contentDescription = "Copy code",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp),
            ) {
                Text(
                    text = code,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 18.sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("Copied", text)
    clipboard.setPrimaryClip(clip)
    Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
}

@Composable
private fun HealthTestResultCard(success: Boolean, label: String, message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (success)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            else
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                if (success) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = if (success)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = if (success)
                    MaterialTheme.colorScheme.onPrimaryContainer
                else
                    MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

private suspend fun testHealthEndpoint(url: String): Pair<Boolean, String> {
    return withContext(Dispatchers.IO) {
        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.requestMethod = "GET"
            val code = connection.responseCode
            connection.disconnect()
            if (code in 200..299) {
                Pair(true, "Health endpoint is live. Status $code.")
            } else {
                Pair(false, "Health test returned status $code.")
            }
        } catch (e: Exception) {
            Pair(false, "Health test failed: ${e.message ?: "Unknown error"}")
        }
    }
}

private fun requestIgnoreBatteryOptimizations(context: Context) {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    if (powerManager.isIgnoringBatteryOptimizations(context.packageName)) {
        return
    }
    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
        data = Uri.parse("package:${context.packageName}")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
}

@Composable
private fun ServerScreenTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    isFocused: Boolean,
    onFocusChanged: (Boolean) -> Unit,
) {
    BasicTextField(
        value = value,
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth().onFocusChanged { onFocusChanged(it.isFocused) },
        onValueChange = onValueChange,
        textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
    ) { innerTextField ->
        Box(
            modifier = Modifier
                .border(
                    width = if (isFocused) 2.dp else 1.dp,
                    color = if (isFocused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    shape = RoundedCornerShape(8.dp),
                )
                .height(40.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(modifier = Modifier.padding(horizontal = 12.dp)) {
                if (value.isEmpty()) {
                    Text(
                        placeholder,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                innerTextField()
            }
        }
    }
}
