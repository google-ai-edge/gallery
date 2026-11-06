/*
 * Copyright 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.ai.edge.gallery.eval

import android.util.Log
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MiniHttpServer(val port: Int, val handler: (Request) -> Response) {
  private var serverSocket: ServerSocket? = null
  private val scope = CoroutineScope(Dispatchers.IO)
  @Volatile private var running = false

  fun start() {
    running = true
    serverSocket = ServerSocket(port)
    scope.launch {
      while (running) {
        try {
          val socket = serverSocket?.accept() ?: break
          scope.launch { handleConnection(socket) }
        } catch (e: Exception) {
          if (running) Log.e(TAG, "Error accepting connection", e)
        }
      }
    }
  }

  fun stop() {
    running = false
    serverSocket?.close()
    serverSocket = null
    scope.cancel()
  }

  private fun handleConnection(socket: Socket) {
    try {
      val input = BufferedInputStream(socket.inputStream)
      val output = socket.outputStream

      val headerBuilder = StringBuilder()
      var currentByte = input.read()

      // Read headers until \r\n\r\n
      while (currentByte != -1) {
        headerBuilder.append(currentByte.toChar())
        if (headerBuilder.endsWith("\r\n\r\n")) break
        currentByte = input.read()
      }

      val headerString = headerBuilder.toString()
      val headerLines = headerString.lines()
      if (headerLines.isEmpty()) return

      val requestLineParts = headerLines[0].split(" ")
      if (requestLineParts.size < 3) return
      val method = requestLineParts[0]
      val path = requestLineParts[1]

      // Parse headers
      val headers = mutableMapOf<String, String>()
      var contentLength = 0
      for (i in 1 until headerLines.size) {
        val line = headerLines[i]
        if (line.isEmpty()) continue
        val parts = line.split(":", limit = 2)
        if (parts.size == 2) {
          val key = parts[0].trim().lowercase()
          val value = parts[1].trim()
          headers[key] = value
          if (key == "content-length") {
            contentLength = value.toIntOrNull() ?: 0
          }
        }
      }

      // Safely read exact number of BYTES for the body
      val body =
        if (contentLength > 0) {
          val bodyBytes = ByteArray(contentLength)
          var bytesRead = 0
          while (bytesRead < contentLength) {
            val read = input.read(bodyBytes, bytesRead, contentLength - bytesRead)
            if (read == -1) break
            bytesRead += read
          }
          String(bodyBytes, Charsets.UTF_8) // Decode to string safely here!
        } else ""

      val request = Request(method, path, headers, body)
      val response = handler(request)
      writeResponse(output, response)
    } catch (e: Exception) {
      Log.e(TAG, "Error handling connection", e)
    } finally {
      socket.close()
    }
  }

  private fun writeResponse(output: OutputStream, response: Response) {
    // Use the byte size of the encoded UTF-8 body to compute Content-Length.
    // Using string character length (response.body.length) would cause response
    // truncation on the client side for responses containing multi-byte characters.
    val bodyBytes = response.body.toByteArray()
    val statusLine = "HTTP/1.1 ${response.status.code} ${response.status.message}\r\n"
    output.write(statusLine.toByteArray())
    output.write("Content-Type: ${response.contentType}\r\n".toByteArray())
    output.write("Content-Length: ${bodyBytes.size}\r\n".toByteArray())
    output.write("Connection: close\r\n".toByteArray())
    output.write("\r\n".toByteArray())
    output.write(bodyBytes)
    output.flush()
  }

  data class Request(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
    val body: String,
  )

  data class Response(val status: Status, val contentType: String, val body: String)

  enum class Status(val code: Int, val message: String) {
    OK(200, "OK"),
    BAD_REQUEST(400, "Bad Request"),
    NOT_FOUND(404, "Not Found"),
    INTERNAL_ERROR(500, "Internal Server Error"),
  }

  companion object {
    private const val TAG = "MiniHttpServer"
  }
}
