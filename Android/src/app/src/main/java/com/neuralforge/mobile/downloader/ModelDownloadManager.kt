/*
 * Copyright 2025 Neural Forge
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

package com.neuralforge.mobile.downloader

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Enhanced model download manager with chunked downloads, resume capability,
 * and intelligent progress tracking.
 */
@Singleton
class ModelDownloadManager @Inject constructor(
    private val context: Context
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .addInterceptor(ProgressInterceptor())
        .addInterceptor(RetryInterceptor(maxRetries = 3))
        .connectionPool(okhttp3.ConnectionPool(5, 5, TimeUnit.MINUTES))
        .build()

    private val downloadDir = File(context.filesDir, "neural_forge_models")

    init {
        if (!downloadDir.exists()) {
            downloadDir.mkdirs()
        }
    }

    /**
     * Download state for tracking progress
     */
    sealed class DownloadState {
        object Preparing : DownloadState()
        data class Downloading(val progress: DownloadProgress) : DownloadState()
        object Merging : DownloadState()
        data class Completed(val file: File) : DownloadState()
        data class Failed(val error: Throwable) : DownloadState()
        data class DownloadingPart(val current: Int, val total: Int) : DownloadState()
    }

    /**
     * Progress information for downloads
     */
    data class DownloadProgress(
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val downloadRate: Float, // MB/s
        val estimatedTimeRemaining: Long, // seconds
        val currentChunk: Int = 1,
        val totalChunks: Int = 1
    ) {
        val progressPercent: Float
            get() = if (totalBytes > 0) (bytesDownloaded.toFloat() / totalBytes * 100) else 0f
    }

    /**
     * Download a model from URL with resume support
     */
    fun downloadModel(
        url: String,
        modelName: String,
        enableResume: Boolean = true,
        chunkSize: Long = 10 * 1024 * 1024 // 10MB chunks for mobile
    ): Flow<DownloadState> = flow {

        emit(DownloadState.Preparing)

        try {
            // Check if partially downloaded
            val partialFile = File(downloadDir, "$modelName.partial")
            val finalFile = File(downloadDir, modelName)

            val startByte = if (enableResume && partialFile.exists()) {
                partialFile.length()
            } else {
                0L
            }

            // Build request with range header for resume
            val request = Request.Builder()
                .url(url)
                .apply {
                    if (startByte > 0) {
                        header("Range", "bytes=$startByte-")
                    }
                }
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful && response.code != 206) {
                    throw IOException("Unexpected response: ${response.code}")
                }

                val body = response.body ?: throw IOException("Empty response body")
                val contentLength = body.contentLength()
                val totalBytes = if (response.code == 206) {
                    // Partial content - add already downloaded bytes
                    contentLength + startByte
                } else {
                    contentLength
                }

                // Stream to storage with progress updates
                val outputFile = if (startByte > 0) partialFile else File(downloadDir, "$modelName.partial")

                outputFile.outputStream().use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(8192)
                        var totalBytesRead = startByte
                        var bytesRead: Int
                        val startTime = System.currentTimeMillis()
                        var lastUpdateTime = startTime

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalBytesRead += bytesRead

                            // Update progress every 500ms
                            val currentTime = System.currentTimeMillis()
                            if (currentTime - lastUpdateTime > 500) {
                                val elapsedSeconds = (currentTime - startTime) / 1000f
                                val downloadRate = (totalBytesRead - startByte) / elapsedSeconds / (1024 * 1024) // MB/s
                                val remainingBytes = totalBytes - totalBytesRead
                                val estimatedRemaining = if (downloadRate > 0) {
                                    (remainingBytes / (downloadRate * 1024 * 1024)).toLong()
                                } else {
                                    0L
                                }

                                val progress = DownloadProgress(
                                    bytesDownloaded = totalBytesRead,
                                    totalBytes = totalBytes,
                                    downloadRate = downloadRate,
                                    estimatedTimeRemaining = estimatedRemaining
                                )

                                emit(DownloadState.Downloading(progress))
                                lastUpdateTime = currentTime
                            }
                        }
                    }
                }

                // Rename to final file
                outputFile.renameTo(finalFile)

                emit(DownloadState.Completed(finalFile))
            }

        } catch (e: Exception) {
            Log.e(TAG, "Download failed for $modelName", e)
            emit(DownloadState.Failed(e))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Download split model parts and merge them
     */
    fun downloadSplitModel(
        modelParts: List<String>,
        modelName: String
    ): Flow<DownloadState> = flow {

        val parts = mutableListOf<File>()

        try {
            modelParts.forEachIndexed { index, url ->
                emit(DownloadState.DownloadingPart(index + 1, modelParts.size))

                downloadModel(url, "$modelName.part$index", true)
                    .collect { state ->
                        when (state) {
                            is DownloadState.Completed -> parts.add(state.file)
                            is DownloadState.Failed -> throw state.error
                            else -> emit(state)
                        }
                    }
            }

            // Merge parts
            emit(DownloadState.Merging)
            val mergedFile = mergeParts(parts, modelName)

            // Clean up part files
            parts.forEach { it.delete() }

            emit(DownloadState.Completed(mergedFile))

        } catch (e: Exception) {
            Log.e(TAG, "Split download failed for $modelName", e)
            emit(DownloadState.Failed(e))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Merge multiple file parts into a single file
     */
    private fun mergeParts(parts: List<File>, outputName: String): File {
        val outputFile = File(downloadDir, outputName)

        outputFile.outputStream().use { output ->
            parts.forEach { part ->
                part.inputStream().use { input ->
                    input.copyTo(output)
                }
            }
        }

        return outputFile
    }

    /**
     * Get the model storage directory
     */
    fun getModelDirectory(): File = downloadDir

    /**
     * Check if a model is already downloaded
     */
    fun isModelDownloaded(modelName: String): Boolean {
        return File(downloadDir, modelName).exists()
    }

    /**
     * Get downloaded model file
     */
    fun getModelFile(modelName: String): File? {
        val file = File(downloadDir, modelName)
        return if (file.exists()) file else null
    }

    /**
     * Delete a downloaded model
     */
    fun deleteModel(modelName: String): Boolean {
        val file = File(downloadDir, modelName)
        return if (file.exists()) {
            file.delete()
        } else {
            false
        }
    }

    companion object {
        private const val TAG = "ModelDownloadManager"
    }
}

/**
 * Interceptor for tracking download progress
 */
class ProgressInterceptor : okhttp3.Interceptor {
    override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
        val originalResponse = chain.proceed(chain.request())
        return originalResponse.newBuilder()
            .body(originalResponse.body?.let { ProgressResponseBody(it) })
            .build()
    }
}

/**
 * Response body wrapper for progress tracking
 */
class ProgressResponseBody(
    private val responseBody: okhttp3.ResponseBody
) : okhttp3.ResponseBody() {

    override fun contentType() = responseBody.contentType()
    override fun contentLength() = responseBody.contentLength()
    override fun source() = responseBody.source()
}

/**
 * Interceptor for automatic retry on network failures
 */
class RetryInterceptor(private val maxRetries: Int = 3) : okhttp3.Interceptor {
    override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
        var attempt = 0
        var response: okhttp3.Response? = null
        var exception: IOException? = null

        while (attempt < maxRetries) {
            try {
                response = chain.proceed(chain.request())
                if (response.isSuccessful || response.code == 206) {
                    return response
                }
                response.close()
            } catch (e: IOException) {
                exception = e
                attempt++
                if (attempt >= maxRetries) {
                    throw e
                }
                // Exponential backoff
                Thread.sleep((1000L * (1 shl attempt)))
            }
        }

        throw exception ?: IOException("Max retries exceeded")
    }
}
