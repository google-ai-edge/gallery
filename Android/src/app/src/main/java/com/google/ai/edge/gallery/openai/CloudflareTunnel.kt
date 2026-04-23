package com.google.ai.edge.gallery.openai

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

private const val TAG = "AGCloudflareTunnel"
private const val CLOUDFLARED_LIB_NAME = "libcloudflared.so"
private const val QUICK_TUNNEL_API_URL = "https://api.trycloudflare.com/tunnel"
private const val CLOUDFLARE_DOH_URL = "https://1.1.1.1/dns-query"
private const val QUICK_TUNNEL_TIMEOUT_MS = 15000
private const val EDGE_DISCOVERY_TIMEOUT_MS = 10000
private const val EDGE_SRV_NAME = "_v2-origintunneld._tcp.argotunnel.com"

class CloudflareTunnel(private val context: Context) {
    private var process: Process? = null
    private val _publicUrl = MutableStateFlow<String?>(null)
    val publicUrl = _publicUrl.asStateFlow()

    suspend fun start(localPort: Int) = withContext(Dispatchers.IO) {
        try {
            val binary = resolveBinary() ?: return@withContext
            val quickTunnel = requestQuickTunnel() ?: return@withContext
            val edgeAddresses = discoverEdgeAddresses()
            if (edgeAddresses.isEmpty()) {
                Log.e(TAG, "Unable to discover Cloudflare edge addresses for tunnel startup")
                return@withContext
            }

            Log.i(TAG, "Starting cloudflared tunnel for port $localPort")
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
            args += listOf(
                "run",
                "--url",
                "http://localhost:$localPort",
            )
            val pb = ProcessBuilder(args)
            // On Android, the child process can inherit an unusable HOME or cwd.
            // Point both at the app sandbox so cloudflared can resolve its temp/config paths.
            pb.directory(context.filesDir)
            pb.environment()["HOME"] = context.filesDir.parentFile?.absolutePath ?: context.filesDir.absolutePath
            pb.environment()["TUNNEL_TOKEN"] = quickTunnel.token
            pb.redirectErrorStream(true)
            _publicUrl.value = quickTunnel.url
            process = pb.start()

            process?.inputStream?.bufferedReader()?.use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    Log.d(TAG, "cloudflared: $line")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start cloudflared tunnel", e)
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

    private fun requestQuickTunnel(): QuickTunnelLaunchData? {
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
            QuickTunnelLaunchData(url = url, token = token)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request quick tunnel", e)
            null
        } finally {
            connection.disconnect()
        }
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

    fun stop() {
        process?.destroy()
        process = null
        _publicUrl.value = null
        Log.i(TAG, "Cloudflare tunnel stopped")
    }
}

private data class QuickTunnelLaunchData(
    val url: String,
    val token: String,
)
