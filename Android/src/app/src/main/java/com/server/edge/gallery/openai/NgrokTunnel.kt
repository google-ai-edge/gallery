package com.server.edge.gallery.openai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "AGNgrokTunnel"
private const val NGROK_LIB_NAME = "libngrok.so"
private const val NGROK_API_URL = "http://127.0.0.1:4040/api/tunnels"
private const val NGROK_READY_TIMEOUT_MS = 30000L
private const val NGROK_READY_RETRY_DELAY_MS = 500L
private const val NGROK_API_TIMEOUT_MS = 4000

class NgrokTunnel(private val context: Context) : PublicTunnel {
    private var process: Process? = null
    private val _publicUrl = MutableStateFlow<String?>(null)
    override val publicUrl: StateFlow<String?> = _publicUrl.asStateFlow()

    override suspend fun start(localPort: Int) = withContext(Dispatchers.IO) {
        stop()
        try {
            val authToken = OpenAiServerState.ngrokAuthToken(context)
            if (authToken.isBlank()) {
                Log.e(TAG, "Ngrok auth token is empty; cannot start tunnel")
                return@withContext
            }

            val binary = resolveBinary() ?: return@withContext
            val configFile = writeConfigFile(authToken)
            val requestedUrl = OpenAiServerState.ngrokDomain(context).trim()

            val args = mutableListOf(
                binary.absolutePath,
                "http",
                localPort.toString(),
                "--config",
                configFile.absolutePath,
                "--log",
                "stdout",
                "--log-format",
                "json",
            )
            if (requestedUrl.isNotBlank()) {
                args += listOf("--url", normalizeUrl(requestedUrl))
            }

            val startedProcess = ProcessBuilder(args).apply {
                directory(context.filesDir)
                environment()["HOME"] =
                    context.filesDir.parentFile?.absolutePath ?: context.filesDir.absolutePath
                redirectErrorStream(true)
            }.start()
            process = startedProcess
            startLogReader(startedProcess)

            val publicUrl = waitForPublicUrl(startedProcess, requestedUrl)
            if (publicUrl == null) {
                Log.e(TAG, "Ngrok tunnel did not become ready in time")
                startedProcess.destroy()
                _publicUrl.value = null
                return@withContext
            }

            _publicUrl.value = publicUrl
            Log.i(TAG, "Ngrok tunnel is ready at $publicUrl")
            startedProcess.waitFor()
            if (_publicUrl.value == publicUrl) {
                _publicUrl.value = null
            }
            Log.w(TAG, "ngrok exited for $publicUrl")
        } catch (error: Exception) {
            Log.e(TAG, "Failed to start ngrok tunnel", error)
            _publicUrl.value = null
        }
    }

    private fun waitForPublicUrl(process: Process, requestedUrl: String): String? {
        val deadline = System.currentTimeMillis() + NGROK_READY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (processHasExited(process)) {
                Log.w(TAG, "ngrok exited before publishing a tunnel URL")
                return null
            }
            queryPublicUrl()?.let { url ->
                val normalizedRequestedUrl = requestedUrl.takeIf { it.isNotBlank() }?.let(::normalizeUrl)
                if (normalizedRequestedUrl == null || normalizedRequestedUrl == url) {
                    return url
                }
            }
            Thread.sleep(NGROK_READY_RETRY_DELAY_MS)
        }
        return null
    }

    private fun queryPublicUrl(): String? {
        val connection = (URL(NGROK_API_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = NGROK_API_TIMEOUT_MS
            readTimeout = NGROK_API_TIMEOUT_MS
            doInput = true
            setRequestProperty("Accept", "application/json")
        }

        return try {
            if (connection.responseCode !in 200..299) {
                null
            } else {
                val responseBody =
                    connection.inputStream.bufferedReader().use { it.readText() }
                extractPublicUrl(JSONObject(responseBody).optJSONArray("tunnels"))
            }
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun extractPublicUrl(tunnels: JSONArray?): String? {
        if (tunnels == null) {
            return null
        }
        for (index in 0 until tunnels.length()) {
            val tunnel = tunnels.optJSONObject(index) ?: continue
            val publicUrl = tunnel.optString("public_url")
            if (publicUrl.startsWith("https://")) {
                return publicUrl.trimEnd('/')
            }
        }
        return null
    }

    private fun writeConfigFile(authToken: String): File {
        val configDir = File(context.filesDir, "ngrok")
        if (!configDir.exists()) {
            configDir.mkdirs()
        }
        val configFile = File(configDir, "ngrok.yml")
        configFile.writeText(
            """
            version: 3
            agent:
              authtoken: $authToken
              dns_resolver_ips:
                - 1.1.1.1
                - 8.8.8.8
              update_check: false
              crl_noverify: true
            """.trimIndent(),
        )
        return configFile
    }

    private fun startLogReader(startedProcess: Process) {
        Thread {
            try {
                startedProcess.inputStream.bufferedReader().use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val output = line.orEmpty()
                        Log.d(TAG, "ngrok: $output")
                    }
                }
            } catch (error: IOException) {
                Log.d(TAG, "ngrok log reader stopped: ${error.message}")
            } catch (error: Exception) {
                Log.d(TAG, "ngrok log reader stopped unexpectedly: ${error.message}")
            }
        }.apply {
            name = "ngrok-log-reader"
            isDaemon = true
            start()
        }
    }

    private fun normalizeUrl(value: String): String {
        val trimmedValue = value.trim().trimStart('.')
        return when {
            trimmedValue.startsWith("https://") || trimmedValue.startsWith("http://") -> trimmedValue.trimEnd('/')
            else -> "https://${trimmedValue.trimEnd('/')}"
        }
    }

    private fun processHasExited(activeProcess: Process): Boolean {
        return try {
            activeProcess.exitValue()
            true
        } catch (_: IllegalThreadStateException) {
            false
        }
    }

    private fun resolveBinary(): File? {
        val nativeDir = context.applicationInfo.nativeLibraryDir ?: return null
        val binaryFile = File(nativeDir, NGROK_LIB_NAME)
        if (binaryFile.exists()) {
            return binaryFile
        }
        Log.e(
            TAG,
            "Could not find $NGROK_LIB_NAME in nativeLibraryDir. Make sure it is packaged under app/src/main/jniLibs for the target ABI.",
        )
        return null
    }

    override fun stop() {
        process?.destroy()
        process = null
        _publicUrl.value = null
        Log.i(TAG, "Ngrok tunnel stopped")
    }
}
