package com.google.ai.edge.gallery.openai

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import java.net.HttpURLConnection
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

private const val TAG = "AGCloudflareTunnel"
private const val CLOUDFLARED_LIB_NAME = "libcloudflared.so"
private const val QUICK_TUNNEL_API_URL = "https://api.trycloudflare.com/tunnel"
private const val CLOUDFLARE_DOH_URL = "https://1.1.1.1/dns-query"
private const val QUICK_TUNNEL_TIMEOUT_MS = 15000
private const val EDGE_DISCOVERY_TIMEOUT_MS = 10000
private const val TUNNEL_READY_TIMEOUT_MS = 45000L
private const val TUNNEL_READY_RETRY_DELAY_MS = 500L
private const val MAX_TUNNEL_START_ATTEMPTS = 3
private const val TUNNEL_CONNECTED_LOG = "Registered tunnel connection"
private const val EDGE_SRV_NAME = "_v2-origintunneld._tcp.argotunnel.com"

class CloudflareTunnel(private val context: Context) : PublicTunnel {
    private var process: Process? = null
    private val _publicUrl = MutableStateFlow<String?>(null)
    override val publicUrl = _publicUrl.asStateFlow()

    override suspend fun start(localPort: Int) = withContext(Dispatchers.IO) {
        stop()
        try {
            val binary = resolveBinary() ?: return@withContext
            val edgeAddresses = discoverEdgeAddresses()
            if (edgeAddresses.isEmpty()) {
                Log.e(TAG, "Unable to discover Cloudflare edge addresses for tunnel startup")
                return@withContext
            }
            val namedTunnel = getNamedTunnelLaunchData()
            if (namedTunnel != null) {
                if (startTunnelAttempt(
                        binary = binary,
                        localPort = localPort,
                        launchData = namedTunnel,
                        edgeAddresses = edgeAddresses,
                    )
                ) {
                    return@withContext
                }
                Log.e(TAG, "Configured Cloudflare named tunnel did not start")
                return@withContext
            }

            repeat(MAX_TUNNEL_START_ATTEMPTS) { attempt ->
                val quickTunnel = requestQuickTunnel() ?: return@repeat
                Log.i(TAG, "Starting cloudflared tunnel attempt ${attempt + 1} for port $localPort")
                if (startTunnelAttempt(
                    binary = binary,
                    localPort = localPort,
                    launchData = quickTunnel,
                    edgeAddresses = edgeAddresses,
                )) {
                    return@withContext
                }
            }
            Log.e(TAG, "Unable to start a reachable Cloudflare tunnel after $MAX_TUNNEL_START_ATTEMPTS attempts")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start cloudflared tunnel", e)
            _publicUrl.value = null
        }
    }

    private fun startTunnelAttempt(
        binary: File,
        localPort: Int,
        launchData: CloudflareTunnelLaunchData,
        edgeAddresses: List<String>,
    ): Boolean {
        val tunnelConnected = AtomicBoolean(false)
        val startedProcess = startCloudflaredProcess(
            binary = binary,
            localPort = localPort,
            launchData = launchData,
            edgeAddresses = edgeAddresses,
        )
        process = startedProcess
        startLogReader(startedProcess) {
            tunnelConnected.set(true)
        }

        if (waitForTunnelConnection(startedProcess, tunnelConnected)) {
            _publicUrl.value = launchData.url
            Log.i(TAG, "Cloudflare tunnel is ready at ${launchData.url}")
            startedProcess.waitFor()
            if (_publicUrl.value == launchData.url) {
                _publicUrl.value = null
            }
            Log.w(TAG, "cloudflared exited for ${launchData.url}")
            return true
        }

        Log.w(TAG, "Tunnel ${launchData.url} did not register a Cloudflare connection; retrying")
        startedProcess.destroy()
        _publicUrl.value = null
        return false
    }

    private fun startCloudflaredProcess(
        binary: File,
        localPort: Int,
        launchData: CloudflareTunnelLaunchData,
        edgeAddresses: List<String>,
    ): Process {
        val args = mutableListOf(
            binary.absolutePath,
            "tunnel",
            "--no-autoupdate",
            "--protocol",
            "http2",
            "--edge-ip-version",
            "4",
        )
        edgeAddresses.forEach { edge ->
            args += "--edge"
            args += edge
        }
        args += if (launchData.namedTunnel) {
            listOf("run", "--token", launchData.token)
        } else {
            listOf(
                "run",
                "--url",
                "http://localhost:$localPort",
            )
        }
        return ProcessBuilder(args).apply {
            // On Android, the child process can inherit an unusable HOME or cwd.
            // Point both at the app sandbox so cloudflared can resolve its temp/config paths.
            directory(context.filesDir)
            environment()["HOME"] = context.filesDir.parentFile?.absolutePath ?: context.filesDir.absolutePath
            if (!launchData.namedTunnel) {
                environment()["TUNNEL_TOKEN"] = launchData.token
            }
            redirectErrorStream(true)
        }.start()
    }

    private fun startLogReader(startedProcess: Process, onTunnelConnected: () -> Unit) {
        Thread {
            try {
                startedProcess.inputStream.bufferedReader().use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val output = line.orEmpty()
                        Log.d(TAG, "cloudflared: $output")
                        if (output.contains(TUNNEL_CONNECTED_LOG)) {
                            onTunnelConnected()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "cloudflared log reader stopped: ${e.message}")
            }
        }.apply {
            name = "cloudflared-log-reader"
            isDaemon = true
            start()
        }
    }

    private fun waitForTunnelConnection(startedProcess: Process, tunnelConnected: AtomicBoolean): Boolean {
        val deadline = System.currentTimeMillis() + TUNNEL_READY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (processHasExited(startedProcess)) {
                Log.w(TAG, "cloudflared exited before registering a tunnel connection")
                return false
            }
            if (tunnelConnected.get()) {
                return true
            }
            Thread.sleep(TUNNEL_READY_RETRY_DELAY_MS)
        }
        return false
    }

    private fun processHasExited(activeProcess: Process): Boolean {
        return try {
            activeProcess.exitValue()
            true
        } catch (_: IllegalThreadStateException) {
            false
        }
    }

    private fun discoverEdgeAddresses(): List<String> {
        val srvRecords = queryDnsAnswerRecords(EDGE_SRV_NAME, "SRV")
        if (srvRecords.isEmpty()) {
            Log.e(TAG, "No SRV records returned for $EDGE_SRV_NAME")
            return emptyList()
        }

        val edges = linkedSetOf<String>()
        for (record in srvRecords) {
            val parts = record.trim().split(Regex("\\s+"))
            if (parts.size < 4) {
                Log.w(TAG, "Unexpected SRV record format: $record")
                continue
            }
            val port = parts[2]
            val target = parts[3].trimEnd('.')
            val ipv4Records = queryDnsAnswerRecords(target, "A")
            for (ip in ipv4Records) {
                if (ip.isNotBlank()) {
                    edges += "$ip:$port"
                }
            }
        }

        Log.i(TAG, "Resolved ${edges.size} static Cloudflare edge addresses")
        return edges.toList()
    }

    private fun queryDnsAnswerRecords(name: String, type: String): List<String> {
        val encodedName = java.net.URLEncoder.encode(name, Charsets.UTF_8.name())
        val requestUrl = "$CLOUDFLARE_DOH_URL?name=$encodedName&type=$type&ct=application/dns-json"
        val connection = (URL(requestUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = EDGE_DISCOVERY_TIMEOUT_MS
            readTimeout = EDGE_DISCOVERY_TIMEOUT_MS
            doInput = true
            setRequestProperty("Accept", "application/dns-json")
            setRequestProperty("User-Agent", "AI-Edge-Gallery-Android")
        }

        return try {
            val responseCode = connection.responseCode
            val responseBody = (if (responseCode in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()

            if (responseCode !in 200..299) {
                Log.e(TAG, "DoH request failed for $name/$type: HTTP $responseCode $responseBody")
                return emptyList()
            }

            val answers = JSONObject(responseBody).optJSONArray("Answer") ?: return emptyList()
            buildList {
                for (index in 0 until answers.length()) {
                    val answer = answers.optJSONObject(index) ?: continue
                    val data = answer.optString("data")
                    if (data.isNotBlank()) {
                        add(data)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed DoH lookup for $name/$type", e)
            emptyList()
        } finally {
            connection.disconnect()
        }
    }

    private fun requestQuickTunnel(): CloudflareTunnelLaunchData? {
        val connection = (URL(QUICK_TUNNEL_API_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = QUICK_TUNNEL_TIMEOUT_MS
            readTimeout = QUICK_TUNNEL_TIMEOUT_MS
            doInput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", "AI-Edge-Gallery-Android")
        }

        return try {
            val responseCode = connection.responseCode
            val responseBody = (if (responseCode in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()

            if (responseCode !in 200..299) {
                Log.e(TAG, "Quick tunnel request failed: HTTP $responseCode $responseBody")
                return null
            }

            val result = JSONObject(responseBody).optJSONObject("result")
            if (result == null) {
                Log.e(TAG, "Quick tunnel response missing result: $responseBody")
                return null
            }

            val hostname = result.optString("hostname")
            val accountTag = result.optString("account_tag")
            val secret = result.optString("secret")
            val tunnelId = result.optString("id")
            if (hostname.isBlank() || accountTag.isBlank() || secret.isBlank() || tunnelId.isBlank()) {
                Log.e(TAG, "Quick tunnel response missing fields: $responseBody")
                return null
            }

            val tokenPayload = JSONObject()
                .put("a", accountTag)
                .put("s", secret)
                .put("t", tunnelId)
                .toString()
            val token = Base64.encodeToString(tokenPayload.toByteArray(), Base64.NO_WRAP)
            val url = if (hostname.startsWith("https://")) hostname else "https://$hostname"
            Log.i(TAG, "Quick tunnel prepared for $url")
            CloudflareTunnelLaunchData(url = url, token = token, namedTunnel = false)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request quick tunnel", e)
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun getNamedTunnelLaunchData(): CloudflareTunnelLaunchData? {
        val token = OpenAiServerState.cloudflareTunnelToken(context)
        val publicUrl = OpenAiServerState.cloudflarePublicUrl(context)
        if (token.isBlank() || publicUrl.isBlank()) {
            return null
        }
        val url = if (publicUrl.startsWith("https://") || publicUrl.startsWith("http://")) {
            publicUrl.trimEnd('/')
        } else {
            "https://${publicUrl.trimEnd('/')}"
        }
        Log.i(TAG, "Using configured Cloudflare named tunnel for $url")
        return CloudflareTunnelLaunchData(url = url, token = token, namedTunnel = true)
    }

    private fun resolveBinary(): File? {
        val nativeDir = context.applicationInfo.nativeLibraryDir ?: return null
        val binaryFile = File(nativeDir, CLOUDFLARED_LIB_NAME)
        if (binaryFile.exists()) {
            return binaryFile
        }
        Log.e(
            TAG,
            "Could not find $CLOUDFLARED_LIB_NAME in nativeLibraryDir. Make sure it is packaged under app/src/main/jniLibs for the target ABI."
        )
        return null
    }

    override fun stop() {
        process?.destroy()
        process = null
        _publicUrl.value = null
        Log.i(TAG, "Cloudflare tunnel stopped")
    }
}

private data class CloudflareTunnelLaunchData(
    val url: String,
    val token: String,
    val namedTunnel: Boolean,
)
