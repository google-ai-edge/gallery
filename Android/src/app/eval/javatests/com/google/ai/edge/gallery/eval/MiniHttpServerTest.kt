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

import com.google.common.truth.Truth.assertThat
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for [MiniHttpServer] to verify socket handling, request parsing, and routing logic.
 */
@RunWith(RobolectricTestRunner::class)
class MiniHttpServerTest {

  private lateinit var server: MiniHttpServer
  private val port = 8081

  @Before
  fun setUp() {
    server =
      MiniHttpServer(port) { request ->
        if (request.path == "/test") {
          MiniHttpServer.Response(MiniHttpServer.Status.OK, "text/plain", "Test OK")
        } else {
          MiniHttpServer.Response(MiniHttpServer.Status.NOT_FOUND, "text/plain", "Not Found")
        }
      }
    server.start()
  }

  @After
  fun tearDown() {
    server.stop()
  }

  @Test
  fun handleRequest_validRoute_returnsOk() {
    val url = URL("http://localhost:$port/test")
    val connection = url.openConnection() as HttpURLConnection
    connection.requestMethod = "GET"

    assertThat(connection.responseCode).isEqualTo(200)

    val response = BufferedReader(InputStreamReader(connection.inputStream)).readText()
    assertThat(response).isEqualTo("Test OK")
  }

  @Test
  fun handleRequest_invalidRoute_returnsNotFound() {
    val url = URL("http://localhost:$port/unknown")
    val connection = url.openConnection() as HttpURLConnection
    connection.requestMethod = "GET"

    assertThat(connection.responseCode).isEqualTo(404)
  }

  @Test
  fun handleRequest_emptyRequest_returnsEarly() {
    val socket = java.net.Socket("localhost", port)
    socket.getOutputStream().close() // Immediately close
    socket.close() // Should return early without throwing
  }

  @Test
  fun handleRequest_malformedRequest_returnsEarly() {
    val socket = java.net.Socket("localhost", port)
    val out = socket.getOutputStream()
    out.write("GET\r\n\r\n".toByteArray())
    out.flush()
    socket.close()
  }

  @Test
  fun handleRequest_headersWithEmptyLinesAndContentLength_doesNotCrash() {
    val socket = java.net.Socket("localhost", port)
    val out = socket.getOutputStream()
    val req = "POST /test HTTP/1.1\r\nContent-Length: 4\r\n\r\n\r\nbody"
    out.write(req.toByteArray())
    out.flush()
    val response =
      java.io.BufferedReader(java.io.InputStreamReader(socket.getInputStream())).readText()
    assertThat(response).contains("200 OK")
    socket.close()
  }
}
