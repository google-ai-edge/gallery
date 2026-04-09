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

package com.google.ai.edge.gallery.customtasks.metasploitagent

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

private const val TAG = "MSFApiClient"
private const val TIMEOUT_MS = 30_000

/**
 * Lightweight HTTP client for the Metasploit Framework RPC API.
 *
 * The MSF RPC service (`msfrpcd`) exposes a JSON API at `/api/1.1/`.
 * Every request is a JSON array where the first element is the method name,
 * the second element is the auth token (omitted for `auth.login`), and the
 * remaining elements are method arguments.
 *
 * Start msfrpcd on the device or a remote host:
 *   msfrpcd -P <password> -U msf -S   # SSL enabled (recommended)
 *   msfrpcd -P <password> -U msf -n   # no SSL, port 55553
 */
class MetasploitApiClient {
  var host: String = "localhost"
  var port: Int = 55553
  var ssl: Boolean = false
  var token: String = ""

  val isConnected: Boolean
    get() = token.isNotEmpty()

  private val baseUrl: String
    get() = "${if (ssl) "https" else "http"}://$host:$port/api/1.1/"

  /**
   * Authenticate with the Metasploit RPC service.
   *
   * Stores the returned token for subsequent calls.
   * Throws [Exception] if authentication fails.
   */
  fun login(
    host: String,
    port: Int,
    ssl: Boolean,
    username: String,
    password: String,
  ): String {
    this.host = host
    this.port = port
    this.ssl = ssl
    this.token = ""

    val response = callRaw("auth.login", username, password)
    val result = response.optString("result")
    if (result != "success") {
      val msg = response.optString("error_message", "Login failed (result=$result)")
      throw Exception(msg)
    }
    this.token = response.getString("token")
    return this.token
  }

  /** Invalidate the current session token. */
  fun logout() {
    if (token.isNotEmpty()) {
      runCatching { callRaw("auth.logout", token, token) }
      token = ""
    }
  }

  /**
   * Call an authenticated Metasploit RPC method.
   *
   * The token is automatically inserted as the second element of the request array.
   */
  fun call(method: String, vararg args: Any): JSONObject {
    check(token.isNotEmpty()) { "Not connected. Call msfConnect first." }
    // Build args array: [method, token, arg0, arg1, ...]
    val fullArgs = Array(args.size + 2) { i ->
      when (i) {
        0 -> method
        1 -> token
        else -> args[i - 2]
      }
    }
    return callRaw(*fullArgs)
  }

  /**
   * Low-level JSON-RPC call with no automatic token injection.
   * Used internally for `auth.login` and `auth.logout`.
   */
  fun callRaw(vararg args: Any): JSONObject {
    val body = JSONArray().apply { args.forEach { put(it) } }.toString()
    Log.d(TAG, "→ ${args.firstOrNull()} | url=$baseUrl")

    val url = URL(baseUrl)
    val conn: HttpURLConnection =
      if (ssl) {
        val https = url.openConnection() as HttpsURLConnection
        https.sslSocketFactory = trustAllSslSocketFactory()
        https.hostnameVerifier = { _, _ -> true }
        https
      } else {
        url.openConnection() as HttpURLConnection
      }

    return try {
      conn.requestMethod = "POST"
      conn.setRequestProperty("Content-Type", "application/json")
      conn.setRequestProperty("Accept", "application/json")
      conn.doOutput = true
      conn.connectTimeout = TIMEOUT_MS
      conn.readTimeout = TIMEOUT_MS

      OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }

      val raw =
        if (conn.responseCode in 200..299) {
          conn.inputStream.bufferedReader().readText()
        } else {
          conn.errorStream?.bufferedReader()?.readText()
            ?: """{"error": "HTTP ${conn.responseCode}"}"""
        }
      Log.d(TAG, "← ${raw.take(300)}")
      JSONObject(raw)
    } finally {
      conn.disconnect()
    }
  }

  // Trust-all SSL socket factory for self-signed msfrpcd certificates.
  private fun trustAllSslSocketFactory(): javax.net.ssl.SSLSocketFactory {
    val trustAll =
      object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
      }
    val sc = SSLContext.getInstance("TLS")
    sc.init(null, arrayOf<TrustManager>(trustAll), java.security.SecureRandom())
    return sc.socketFactory
  }
}
