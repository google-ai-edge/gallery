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

package com.google.ai.edge.gallery.server

import android.content.Context
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "AGFrpManager"

@Singleton
class FrpManager @Inject constructor(@ApplicationContext private val context: Context) {
  private var process: Process? = null
  private val frpScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val _isRunning = MutableStateFlow(false)
  val isRunning = _isRunning.asStateFlow()

  fun isBinaryAvailable(): Boolean {
    val binary = getFrpcBinary()
    return binary != null && binary.exists()
  }

  fun importBinary(uri: Uri): Boolean {
    return try {
      val targetFile = File(context.filesDir, "frpc")
      context.contentResolver.openInputStream(uri)?.use { input ->
        targetFile.outputStream().use { output ->
          input.copyTo(output)
        }
      }
      targetFile.setExecutable(true)
      Log.i(TAG, "frpc binary imported successfully to ${targetFile.absolutePath}")
      true
    } catch (e: Exception) {
      Log.e(TAG, "Failed to import frpc binary", e)
      false
    }
  }

  fun deleteBinary(): Boolean {
    val file = File(context.filesDir, "frpc")
    return if (file.exists()) {
      file.delete()
    } else {
      true
    }
  }

  fun start(serverAddr: String, serverPort: Int, token: String, localPort: Int, remotePort: Int, customDomain: String = "") {
    if (_isRunning.value) stop()

    val frpcBinary = getFrpcBinary()
    if (frpcBinary == null || !frpcBinary.exists()) {
      val msg = "frpc binary not found. Please run: adb push frpc /data/data/com.google.aiedge.gallery/files/frpc"
      Log.e(TAG, msg)
      return
    }

    if (!frpcBinary.canExecute()) {
      frpcBinary.setExecutable(true)
    }

    val configFile = File(context.filesDir, "frpc.toml")
    val proxyConfig = if (customDomain.isNotEmpty()) {
      """
      type = "http"
      localPort = $localPort
      customDomains = ["$customDomain"]
      """
    } else {
      """
      type = "tcp"
      localPort = $localPort
      remotePort = $remotePort
      """
    }

    configFile.writeText("""
      serverAddr = "$serverAddr"
      serverPort = $serverPort
      auth.token = "$token"

      [[proxies]]
      name = "openai-api"
      $proxyConfig
    """.trimIndent())

    frpScope.launch {
      try {
        Log.i(TAG, "Starting frpc...")
        val builder = ProcessBuilder(frpcBinary.absolutePath, "-c", configFile.absolutePath)
          .directory(context.filesDir)
          .redirectErrorStream(true)
        
        val p = builder.start()
        process = p
        _isRunning.value = true

        p.inputStream.bufferedReader().use { reader ->
          var line: String?
          while (reader.readLine().also { line = it } != null) {
            Log.d(TAG, "frpc: $line")
          }
        }
      } catch (e: Exception) {
        Log.e(TAG, "Error running frpc", e)
      } finally {
        _isRunning.value = false
        process = null
      }
    }
  }

  fun stop() {
    process?.destroy()
    process = null
    _isRunning.value = false
  }

  private fun getFrpcBinary(): File? {
    // Expected binary name based on architecture
    val arch = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: return null
    val binaryName = when {
      arch.contains("arm64") -> "frpc_android_arm64"
      arch.contains("armeabi") -> "frpc_android_arm"
      arch.contains("x86_64") -> "frpc_android_amd64"
      else -> "frpc"
    }
    
    // Check in files directory
    val file = File(context.filesDir, "frpc")
    if (file.exists()) return file
    
    val archFile = File(context.filesDir, binaryName)
    if (archFile.exists()) return archFile
    
    return null
  }
}
