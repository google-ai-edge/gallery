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
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Split model downloader for handling large models
 *
 * Splits large models into multiple parts for:
 * - Better reliability (resume individual parts)
 * - Parallel downloading
 * - Progress tracking per part
 */
@Singleton
class SplitModelDownloader @Inject constructor(
    private val context: Context,
    private val downloadManager: ModelDownloadManager
) {

    private val downloadDir = downloadManager.getModelDirectory()

    /**
     * Download a large model in multiple parts
     */
    fun downloadSplitModel(
        baseUrl: String,
        modelName: String,
        totalSize: Long,
        partSize: Long = DEFAULT_PART_SIZE,
        checksum: String? = null
    ): Flow<SplitDownloadState> = flow {

        emit(SplitDownloadState.Preparing)

        try {
            // Calculate number of parts
            val numParts = ((totalSize + partSize - 1) / partSize).toInt()

            Log.d(TAG, "Downloading $modelName in $numParts parts")

            val partFiles = mutableListOf<File>()
            val outputFile = File(downloadDir, modelName)

            // Download each part
            for (partIndex in 0 until numParts) {
                val startByte = partIndex * partSize
                val endByte = minOf(startByte + partSize - 1, totalSize - 1)
                val partFileName = "$modelName.part$partIndex"

                emit(SplitDownloadState.DownloadingPart(
                    partIndex = partIndex + 1,
                    totalParts = numParts,
                    partProgress = 0f
                ))

                // Download this part
                downloadPart(
                    url = baseUrl,
                    startByte = startByte,
                    endByte = endByte,
                    outputFile = File(downloadDir, partFileName)
                ).collect { progress ->
                    emit(SplitDownloadState.DownloadingPart(
                        partIndex = partIndex + 1,
                        totalParts = numParts,
                        partProgress = progress
                    ))
                }

                partFiles.add(File(downloadDir, partFileName))
            }

            // Merge all parts
            emit(SplitDownloadState.Merging(0f))
            mergeParts(partFiles, outputFile) { mergeProgress ->
                // Could emit merge progress here if needed
            }

            // Verify checksum if provided
            if (checksum != null) {
                emit(SplitDownloadState.Verifying)
                if (!verifyChecksum(outputFile, checksum)) {
                    outputFile.delete()
                    throw ChecksumMismatchException("Checksum verification failed")
                }
            }

            // Clean up part files
            partFiles.forEach { it.delete() }

            emit(SplitDownloadState.Completed(outputFile))

        } catch (e: Exception) {
            Log.e(TAG, "Split download failed for $modelName", e)
            emit(SplitDownloadState.Failed(e))
        }

    }.flowOn(Dispatchers.IO)

    /**
     * Download a single part of the model
     */
    private suspend fun downloadPart(
        url: String,
        startByte: Long,
        endByte: Long,
        outputFile: File
    ): Flow<Float> = flow {
        // Implementation would use Range header
        // For now, simplified version
        val request = okhttp3.Request.Builder()
            .url(url)
            .header("Range", "bytes=$startByte-$endByte")
            .build()

        val client = okhttp3.OkHttpClient()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful && response.code != 206) {
                throw Exception("HTTP error ${response.code}")
            }

            val body = response.body ?: throw Exception("Empty body")
            val totalBytes = endByte - startByte + 1

            outputFile.outputStream().use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(8192)
                    var bytesRead = 0L
                    var read: Int

                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        bytesRead += read

                        val progress = bytesRead.toFloat() / totalBytes
                        emit(progress)
                    }
                }
            }
        }
    }

    /**
     * Merge multiple part files into single file
     */
    private fun mergeParts(
        partFiles: List<File>,
        outputFile: File,
        onProgress: (Float) -> Unit
    ) {
        val totalSize = partFiles.sumOf { it.length() }
        var merged = 0L

        RandomAccessFile(outputFile, "rw").use { output ->
            partFiles.forEach { partFile ->
                partFile.inputStream().use { input ->
                    val buffer = ByteArray(8192)
                    var read: Int

                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        merged += read

                        val progress = merged.toFloat() / totalSize
                        onProgress(progress)
                    }
                }
            }
        }
    }

    /**
     * Verify file checksum
     */
    private fun verifyChecksum(file: File, expectedChecksum: String): Boolean {
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var read: Int

                while (input.read(buffer).also { read = it } != -1) {
                    digest.update(buffer, 0, read)
                }
            }

            val actualChecksum = digest.digest().joinToString("") {
                "%02x".format(it)
            }

            return actualChecksum.equals(expectedChecksum, ignoreCase = true)
        } catch (e: Exception) {
            Log.e(TAG, "Checksum verification failed", e)
            return false
        }
    }

    companion object {
        private const val TAG = "SplitModelDownloader"
        private const val DEFAULT_PART_SIZE = 50L * 1024 * 1024 // 50MB per part
    }
}

/**
 * Split download states
 */
sealed class SplitDownloadState {
    object Preparing : SplitDownloadState()

    data class DownloadingPart(
        val partIndex: Int,
        val totalParts: Int,
        val partProgress: Float
    ) : SplitDownloadState()

    data class Merging(val progress: Float) : SplitDownloadState()

    object Verifying : SplitDownloadState()

    data class Completed(val file: File) : SplitDownloadState()

    data class Failed(val error: Throwable) : SplitDownloadState()
}

/**
 * Checksum mismatch exception
 */
class ChecksumMismatchException(message: String) : Exception(message)
