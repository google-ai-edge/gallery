/*
 * Copyright 2026 Pascal Fritzsche
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

package com.google.ai.edge.gallery.morgenschiss

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

const val MORGENSCHISS_BASE_URL = "https://morgenschiss.de"

/** Outcome of one call; the app falls back to local work on everything but [Ok]. */
sealed interface ApiResult<out T> {
  data class Ok<T>(val value: T) : ApiResult<T>

  /** Session missing or expired (403 on POST, 302 to /login on GET). */
  data object LoggedOut : ApiResult<Nothing>

  /** Logged in, but the account lacks app:MediaSearch (server answers with an HTML page). */
  data object NoAccess : ApiResult<Nothing>

  /** Mac or server not reachable: 503, network error or timeout. */
  data class Unavailable(val reason: String) : ApiResult<Nothing>

  /** 429: rate limit or a previous index batch still running. */
  data class Busy(val code: String) : ApiResult<Nothing>

  data class Failed(val status: Int, val code: String) : ApiResult<Nothing>
}

data class Session(val user: String, val token: String)

val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

/** Login, token storage and plain HTTP calls to morgenschiss. */
@Singleton
class MorgenschissClient @Inject constructor(@ApplicationContext context: Context) {
  private val prefs: SharedPreferences =
    EncryptedSharedPreferences.create(
      context,
      "morgenschiss_session",
      MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
      EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
      EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

  private val _session = MutableStateFlow(readSession())
  val session: StateFlow<Session?> = _session.asStateFlow()

  private fun readSession(): Session? {
    val user = prefs.getString("user", null) ?: return null
    val token = prefs.getString("token", null) ?: return null
    return Session(user, token)
  }

  val isLoggedIn: Boolean
    get() = _session.value != null

  /** Returns null on success, otherwise a German message for the login form. */
  suspend fun login(user: String, password: String): String? =
    withContext(Dispatchers.IO) {
      val conn = open("/api/login", "POST", timeoutMs = 15_000)
      try {
        conn.setRequestProperty("us", user.trim())
        // the server compares the SHA-256 hex of the password; the password itself never leaves
        // the phone and is not stored
        conn.setRequestProperty("pw", sha256Hex(password))
        conn.setRequestProperty("stayan", "true")
        conn.doOutput = true
        conn.outputStream.use {}
        when (val status = conn.responseCode) {
          200 -> {
            val token = conn.inputStream.bufferedReader().use { it.readText() }.trim()
            if (token.isEmpty() || token.length > 512) return@withContext "Unerwartete Antwort vom Server."
            prefs.edit().putString("user", user.trim()).putString("token", token).apply()
            _session.value = Session(user.trim(), token)
            null
          }
          401 -> "Benutzername oder Passwort falsch."
          202 -> "Das Konto ist noch nicht freigegeben."
          423 -> "Das Konto ist gesperrt."
          429 -> "Zu viele Versuche, bitte später erneut."
          else -> "Anmeldung fehlgeschlagen (0)."
        }
      } catch (e: IOException) {
        "morgenschiss ist nicht erreichbar."
      } finally {
        conn.disconnect()
      }
    }

  fun logout() {
    prefs.edit().clear().apply()
    _session.value = null
  }

  /** JSON call; [body] null means GET. */
  suspend fun call(
    path: String,
    body: String? = null,
    timeoutMs: Int = 10_000,
  ): ApiResult<JsonElement> =
    withContext(Dispatchers.IO) {
      val session = _session.value ?: return@withContext ApiResult.LoggedOut
      val conn = open(path, if (body == null) "GET" else "POST", timeoutMs)
      try {
        conn.instanceFollowRedirects = false
        conn.setRequestProperty("Cookie", "session=${session.token}")
        if (body != null) {
          conn.doOutput = true
          conn.setRequestProperty("Content-Type", "application/json")
          conn.outputStream.use { it.write(body.toByteArray()) }
        }
        readResult(conn)
      } catch (e: SocketTimeoutException) {
        ApiResult.Unavailable("timeout")
      } catch (e: IOException) {
        ApiResult.Unavailable("network")
      } finally {
        conn.disconnect()
      }
    }

  /** Raw upload (e.g. audio) with a content type; same result mapping as [call]. */
  suspend fun upload(path: String, contentType: String, data: ByteArray, timeoutMs: Int = 120_000): ApiResult<JsonElement> =
    withContext(Dispatchers.IO) {
      val session = _session.value ?: return@withContext ApiResult.LoggedOut
      val conn = open(path, "POST", timeoutMs)
      try {
        conn.instanceFollowRedirects = false
        conn.setRequestProperty("Cookie", "session=${session.token}")
        conn.setRequestProperty("Content-Type", contentType)
        conn.doOutput = true
        conn.setFixedLengthStreamingMode(data.size)
        conn.outputStream.use { it.write(data) }
        readResult(conn)
      } catch (e: SocketTimeoutException) {
        ApiResult.Unavailable("timeout")
      } catch (e: IOException) {
        ApiResult.Unavailable("network")
      } finally {
        conn.disconnect()
      }
    }

  private fun readResult(conn: HttpURLConnection): ApiResult<JsonElement> {
    val status = conn.responseCode
    val type = conn.contentType ?: ""
    val stream = if (status >= 400) conn.errorStream else conn.inputStream
    val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
    val code = runCatching { json.parseToJsonElement(text) }.getOrNull()?.let { errorCode(it) } ?: ""
    return mapResponse(status, type, text, code).also { if (it is ApiResult.LoggedOut) logout() }
  }

  private fun open(path: String, method: String, timeoutMs: Int): HttpURLConnection =
    (URL(MORGENSCHISS_BASE_URL + path).openConnection() as HttpURLConnection).apply {
      requestMethod = method
      connectTimeout = minOf(timeoutMs, 5_000)
      readTimeout = timeoutMs
    }

  companion object {
    fun sha256Hex(text: String): String =
      MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun errorCode(el: JsonElement): String? =
      runCatching { (el as kotlinx.serialization.json.JsonObject)["error"]?.toString()?.trim('"') }.getOrNull()

    /** Maps the Interface conventions (see docs/mediasearch/api.md) to [ApiResult]. */
    fun mapResponse(status: Int, contentType: String, text: String, code: String): ApiResult<JsonElement> =
      when {
        status == 302 || status == 401 || status == 403 -> ApiResult.LoggedOut
        status == 503 -> ApiResult.Unavailable(code.ifEmpty { "unavailable" })
        status == 429 -> ApiResult.Busy(code.ifEmpty { "rate_limited" })
        status in 200..299 && !contentType.contains("json") -> ApiResult.NoAccess
        status in 200..299 -> {
          val parsed = runCatching { json.parseToJsonElement(text) }.getOrNull()
          if (parsed != null) ApiResult.Ok(parsed) else ApiResult.Failed(status, "invalid_json")
        }
        status >= 500 -> ApiResult.Unavailable(code.ifEmpty { "server_$status" })
        else -> ApiResult.Failed(status, code.ifEmpty { "http_$status" })
      }
  }
}
