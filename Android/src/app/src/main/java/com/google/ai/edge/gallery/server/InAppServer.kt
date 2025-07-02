package com.google.ai.edge.gallery.server

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.google.ai.edge.gallery.data.TASK_LLM_ASK_IMAGE
import com.google.ai.edge.gallery.ui.llmchat.LlmChatModelHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.util.concurrent.CountDownLatch
import javax.inject.Inject
import javax.inject.Singleton
import org.apache.commons.fileupload.FileItem
import org.apache.commons.fileupload.disk.DiskFileItemFactory
import org.apache.commons.fileupload.servlet.ServletFileUpload

@Singleton
class InAppServer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val llmChatModelHelper: LlmChatModelHelper
) {

    private var serverSocket: ServerSocket? = null
    private var serverThread: Thread? = null
    @Volatile
    private var isServerRunning = false

    fun start() {
        if (isServerRunning) return

        serverThread = Thread {
            try {
                llmChatModelHelper.initialize(context, TASK_LLM_ASK_IMAGE.models.first()) {
                    if (it.isNotEmpty()) {
                        Log.e(TAG, "Failed to initialize model: $it")
                        return@initialize
                    }
                }

                serverSocket = ServerSocket(DEVICE_PORT)
                isServerRunning = true
                Log.i(TAG, "In-App Server started on port " + DEVICE_PORT)

                while (isServerRunning) {
                    try {
                        val clientSocket = serverSocket!!.accept()
                        Log.i(TAG, "Client connected: " + clientSocket.inetAddress)
                        handleClient(clientSocket)
                    } catch (e: SocketException) {
                        if (!isServerRunning) {
                            Log.i(TAG, "Server socket closed intentionally.")
                        } else {
                            Log.e(TAG, "Error accepting connection", e)
                        }
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "Error starting server", e)
                isServerRunning = false
            }
        }
        serverThread!!.start()
    }

    fun stop() {
        if (!isServerRunning) return

        try {
            isServerRunning = false
            if (serverSocket != null && !serverSocket!!.isClosed) {
                serverSocket!!.close()
            }
            if (serverThread != null) {
                serverThread!!.interrupt()
                serverThread = null
            }
            llmChatModelHelper.cleanUp(TASK_LLM_ASK_IMAGE.models.first())
            Log.i(TAG, "In-App Server stopped.")
        } catch (e: IOException) {
            Log.e(TAG, "Error stopping server", e)
        }
    }

    private fun handleClient(clientSocket: Socket) {
        try {
            val inputStream = clientSocket.inputStream
            val writer = PrintWriter(clientSocket.outputStream, true)

            val requestLine = readLine(inputStream)
            if (requestLine.isBlank()) {
                clientSocket.close()
                return
            }
            Log.i(TAG, "Request: $requestLine")

            val requestParts = requestLine.split(" ")
            val method = requestParts[0]

            var contentType = ""
            var contentLength = 0
            var line = readLine(inputStream)
            while (line.isNotEmpty()) {
                if (line.startsWith("Content-Type:", ignoreCase = true)) {
                    contentType = line.substringAfter(":").trim()
                } else if (line.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = line.substringAfter(":").trim().toInt()
                }
                line = readLine(inputStream)
            }

            var prompt = ""
            var imageData: ByteArray? = null

            if (method == "POST") {
                if (contentLength > 0) {
                    val bodyBytes = ByteArray(contentLength)
                    var bytesRead = 0
                    while (bytesRead < contentLength) {
                        val read = inputStream.read(bodyBytes, bytesRead, contentLength - bytesRead)
                        if (read == -1) break
                        bytesRead += read
                    }

                    if (ServletFileUpload.isMultipartContent(RequestContext(ByteArrayInputStream(bodyBytes), contentType, contentLength))) {
                        val factory = DiskFileItemFactory()
                        val upload = ServletFileUpload(factory)
                        val items = upload.parseRequest(RequestContext(ByteArrayInputStream(bodyBytes), contentType, contentLength))
                        for (item in items) {
                            if (item.isFormField) {
                                if (item.fieldName == "prompt") {
                                    prompt = item.string
                                }
                            } else {
                                if (item.fieldName == "image") {
                                    imageData = item.get()
                                }
                            }
                        }
                    } else {
                        prompt = String(bodyBytes)
                    }
                }
            } else { // GET
                val queryParams = getQueryParams(requestLine)
                prompt = queryParams["prompt"] ?: ""
            }

            if (prompt.isBlank() && imageData == null) {
                writer.println("HTTP/1.1 400 Bad Request")
                writer.println("Content-Type: text/plain")
                writer.println()
                writer.println("No prompt or image provided.")
                writer.flush()
                clientSocket.close()
                return
            }

            writer.println("HTTP/1.1 200 OK")
            writer.println("Content-Type: text/plain")
            writer.println("Connection: close")
            writer.println()
            writer.flush()

            val latch = CountDownLatch(1)
            llmChatModelHelper.resetSession(TASK_LLM_ASK_IMAGE.models.first())

            val images: List<Bitmap> = imageData?.let {
                val bitmap = BitmapFactory.decodeByteArray(it, 0, it.size)
                listOf(bitmap)
            } ?: emptyList()

            llmChatModelHelper.runInference(
                model = TASK_LLM_ASK_IMAGE.models.first(),
                input = prompt,
                images = images,
                resultListener = { partialResult, done ->
                    writer.print(partialResult)
                    writer.flush()
                    if (done) {
                        clientSocket.close()
                        latch.countDown()
                    }
                },
                cleanUpListener = {
                    if (!clientSocket.isClosed) {
                        writer.flush()
                        clientSocket.close()
                    }
                    latch.countDown()
                }
            )
            latch.await()
        } catch (e: Exception) {
            Log.e(TAG, "Error handling client", e)
        } finally {
            try {
                if (!clientSocket.isClosed) {
                    clientSocket.close()
                }
            } catch (e: IOException) {
                Log.e(TAG, "Error closing client socket", e)
            }
        }
    }

    private fun readLine(stream: InputStream): String {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val b = stream.read()
            if (b == -1) break
            if (b == '\n'.code) {
                break
            }
            buffer.write(b)
        }
        val bytes = buffer.toByteArray()
        if (bytes.isNotEmpty() && bytes.last() == '\r'.toByte()) {
            return String(bytes, 0, bytes.size - 1, Charsets.ISO_8859_1)
        }
        return String(bytes, Charsets.ISO_8859_1)
    }

    private fun getQueryParams(requestLine: String): Map<String, String> {
        val queryParams = mutableMapOf<String, String>()
        val urlParts = requestLine.split(" ")[1].split("?")
        if (urlParts.size > 1) {
            val query = urlParts[1]
            for (param in query.split("&")) {
                val pair = param.split("=")
                if (pair.size > 1) {
                    queryParams[URLDecoder.decode(pair[0], "UTF-8")] =
                        URLDecoder.decode(pair[1], "UTF-8")
                }
            }
        }
        return queryParams
    }

    fun isRunning(): Boolean {
        return isServerRunning
    }

    companion object {
        private const val TAG = "AIEdgeServer"
        private const val DEVICE_PORT = 8080
    }
}

class RequestContext(
    private val inputStream: java.io.InputStream,
    private val contentType: String,
    private val contentLength: Int
) : org.apache.commons.fileupload.RequestContext {
    override fun getCharacterEncoding(): String {
        return "UTF-8"
    }

    override fun getContentType(): String {
        return contentType
    }

    override fun getContentLength(): Int {
        return contentLength
    }

    override fun getInputStream(): java.io.InputStream {
        return inputStream
    }
}