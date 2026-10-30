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

package com.google.ai.edge.gallery.services.asr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow

private const val TAG = "AGPcmAudioRecorder"
private const val SHORT_NORMALIZATION_DIVISOR = 32768.0f

/**
 * Encapsulates hardware microphone capture ([AudioRecord]) and volume metering for
 * [SpeechProcessorAsrService].
 *
 * Prefers [AudioFormat.ENCODING_PCM_FLOAT] and automatically falls back to
 * [AudioFormat.ENCODING_PCM_16BIT] (normalizing samples into `[-1.0, 1.0]`) for devices whose audio
 * HAL rejects float capture.
 */
internal class PcmAudioRecorder(
  private val context: Context,
  private val sampleRateHz: Int = SpeechProcessorAsrService.SAMPLE_RATE_HZ,
  private val chunkSamples: Int = SpeechProcessorAsrService.CHUNK_SAMPLES,
  private val audioRecordFactory: ((sampleRate: Int, bufferBytes: Int) -> AudioRecord)? = null,
  private val audioChunkSource: (() -> Flow<FloatArray>)? = null,
) {
  private val audioRecordRef = AtomicReference<AudioRecord?>(null)
  private val externalPfdRef = AtomicReference<ParcelFileDescriptor?>(null)

  fun hasRecordPermission(): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
      PackageManager.PERMISSION_GRANTED

  /**
   * Streams 16 kHz mono float chunks into [audioChannel], updating [onAmplitude] on each chunk, and
   * closes [audioChannel] when finished.
   */
  suspend fun streamAudioChunks(
    audioChannel: SendChannel<FloatArray>,
    isStopped: () -> Boolean,
    onAmplitude: (Float) -> Unit,
    externalAudioSource: (() -> ParcelFileDescriptor?)? = null,
  ) {
    val externalPfd =
      if (externalAudioSource != null) {
        try {
          externalAudioSource.invoke()
        } catch (e: CancellationException) {
          audioChannel.close()
          throw e
        } catch (e: Exception) {
          audioChannel.close()
          Log.e(TAG, "External audio source factory failed", e)
          throw AsrServiceError.AudioRecordingFailed(e)
        }
      } else {
        null
      }
    if (externalPfd != null) {
      streamFromExternalPfd(
        externalPfd = externalPfd,
        audioChannel = audioChannel,
        isStopped = isStopped,
        onAmplitude = onAmplitude,
      )
      return
    }

    if (!hasRecordPermission()) {
      audioChannel.close()
      throw AsrServiceError.AudioPermissionDenied()
    }

    val customSource = audioChunkSource
    if (customSource != null) {
      try {
        if (!isStopped()) {
          customSource().collect { samples ->
            if (isStopped()) return@collect
            currentCoroutineContext().ensureActive()
            onAmplitude(samples.calculateNormalizedVolume())
            val unused = audioChannel.trySend(samples)
          }
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.e(TAG, "Custom audio chunk source failed", e)
        throw AsrServiceError.AudioRecordingFailed(e)
      } finally {
        audioChannel.close()
      }
      return
    }

    var record: AudioRecord? = null
    try {
      if (isStopped()) return
      val (createdRecord, isFloatEncoding) = createInitializedAudioRecord()
      record = createdRecord
      audioRecordRef.getAndSet(createdRecord)?.let { stale -> stopAndRelease(stale) }
      if (isStopped()) return
      createdRecord.startRecording()
      val floatBuffer = FloatArray(chunkSamples)
      val shortBuffer = if (!isFloatEncoding) ShortArray(chunkSamples) else null
      while (!isStopped()) {
        currentCoroutineContext().ensureActive()
        val readCount =
          if (shortBuffer == null) {
            createdRecord.read(floatBuffer, 0, floatBuffer.size, AudioRecord.READ_BLOCKING)
          } else {
            createdRecord.read(shortBuffer, 0, shortBuffer.size, AudioRecord.READ_BLOCKING)
          }
        if (readCount < 0) {
          if (!isStopped()) {
            Log.e(TAG, "AudioRecord.read failed with error code: $readCount")
            throw AsrServiceError.AudioRecordingFailed()
          }
          break
        }
        if (readCount == 0) continue
        if (shortBuffer != null) {
          for (i in 0 until readCount) {
            floatBuffer[i] = shortBuffer[i] / SHORT_NORMALIZATION_DIVISOR
          }
        }
        val samples =
          if (readCount == floatBuffer.size) {
            floatBuffer.copyOf()
          } else {
            floatBuffer.copyOf(readCount)
          }
        onAmplitude(samples.calculateNormalizedVolume())
        val unused = audioChannel.trySend(samples)
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: AsrServiceError) {
      throw e
    } catch (e: Exception) {
      Log.e(TAG, "AudioRecord capture failed", e)
      throw AsrServiceError.AudioRecordingFailed(e)
    } finally {
      if (record != null && audioRecordRef.compareAndSet(record, null)) {
        stopAndRelease(record)
      }
      audioChannel.close()
    }
  }

  private suspend fun streamFromExternalPfd(
    externalPfd: ParcelFileDescriptor,
    audioChannel: SendChannel<FloatArray>,
    isStopped: () -> Boolean,
    onAmplitude: (Float) -> Unit,
  ) {
    externalPfdRef.getAndSet(externalPfd)?.let { stale -> runCatching { stale.close() } }
    try {
      ParcelFileDescriptor.AutoCloseInputStream(externalPfd).use { inputStream ->
        val byteBuffer = ByteArray(chunkSamples * Short.SIZE_BYTES)
        var carryByte = -1
        while (!isStopped()) {
          currentCoroutineContext().ensureActive()
          val offset =
            if (carryByte >= 0) {
              byteBuffer[0] = carryByte.toByte()
              carryByte = -1
              1
            } else {
              0
            }
          val bytesRead =
            try {
              inputStream.read(byteBuffer, offset, byteBuffer.size - offset)
            } catch (e: IOException) {
              if (isStopped()) break
              throw e
            }
          if (bytesRead < 0) break
          val totalBytes = offset + bytesRead
          if (totalBytes < Short.SIZE_BYTES) {
            if (totalBytes == 1) carryByte = byteBuffer[0].toInt() and 0xFF
            continue
          }
          if (totalBytes % Short.SIZE_BYTES != 0) {
            carryByte = byteBuffer[totalBytes - 1].toInt() and 0xFF
          }
          // `AudioTranscriber` consumes float PCM, so each chunk is a new `FloatArray`.
          val samples =
            FloatArray(totalBytes / Short.SIZE_BYTES) { i ->
              val lo = byteBuffer[i * 2].toInt() and 0xFF
              val hi = byteBuffer[i * 2 + 1].toInt()
              ((hi shl 8) or lo).toShort() / SHORT_NORMALIZATION_DIVISOR
            }
          onAmplitude(samples.calculateNormalizedVolume())
          val unused = audioChannel.trySend(samples)
        }
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: AsrServiceError) {
      throw e
    } catch (e: Exception) {
      if (!isStopped()) {
        Log.e(TAG, "External audio source read failed", e)
        throw AsrServiceError.AudioRecordingFailed(e)
      }
    } finally {
      if (externalPfdRef.compareAndSet(externalPfd, null)) {
        runCatching { externalPfd.close() }
      }
      audioChannel.close()
    }
  }

  fun release() {
    externalPfdRef.getAndSet(null)?.let { pfd -> runCatching { pfd.close() } }
    val record = audioRecordRef.getAndSet(null) ?: return
    stopAndRelease(record)
  }

  private fun createInitializedAudioRecord(): Pair<AudioRecord, Boolean> {
    var isFloatEncoding = true
    var minBufferBytes =
      AudioRecord.getMinBufferSize(
        sampleRateHz,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_FLOAT,
      )
    if (minBufferBytes <= 0) {
      isFloatEncoding = false
      minBufferBytes =
        AudioRecord.getMinBufferSize(
          sampleRateHz,
          AudioFormat.CHANNEL_IN_MONO,
          AudioFormat.ENCODING_PCM_16BIT,
        )
    }
    if (minBufferBytes <= 0) {
      throw AsrServiceError.AudioRecordingFailed()
    }
    val sampleBytes = if (isFloatEncoding) Float.SIZE_BYTES else Short.SIZE_BYTES
    val bufferBytes = max(minBufferBytes, sampleRateHz * sampleBytes * 8)

    val record =
      try {
        when {
          audioRecordFactory != null -> audioRecordFactory.invoke(sampleRateHz, bufferBytes)
          isFloatEncoding -> {
            val floatRecord = instantiateAudioRecord(AudioFormat.ENCODING_PCM_FLOAT, bufferBytes)
            if (floatRecord.state != AudioRecord.STATE_INITIALIZED) {
              floatRecord.release()
              isFloatEncoding = false
              instantiateFallback16BitRecord()
            } else {
              floatRecord
            }
          }
          else -> instantiateAudioRecord(AudioFormat.ENCODING_PCM_16BIT, bufferBytes)
        }
      } catch (e: Exception) {
        if (isFloatEncoding && audioRecordFactory == null) {
          try {
            isFloatEncoding = false
            instantiateFallback16BitRecord()
          } catch (e2: Exception) {
            Log.e(TAG, "Failed to instantiate AudioRecord (fallback 16bit)", e2)
            null
          }
        } else {
          Log.e(TAG, "Failed to instantiate AudioRecord", e)
          null
        }
      }

    if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
      record?.release()
      throw AsrServiceError.AudioRecordingFailed()
    }
    return record to isFloatEncoding
  }

  private fun instantiateFallback16BitRecord(): AudioRecord {
    val min16Bytes =
      AudioRecord.getMinBufferSize(
        sampleRateHz,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
      )
    val buffer16Bytes = max(min16Bytes, sampleRateHz * Short.SIZE_BYTES * 8)
    return instantiateAudioRecord(AudioFormat.ENCODING_PCM_16BIT, buffer16Bytes)
  }

  private fun instantiateAudioRecord(encoding: Int, bufferBytes: Int): AudioRecord =
    AudioRecord(
      MediaRecorder.AudioSource.VOICE_RECOGNITION,
      sampleRateHz,
      AudioFormat.CHANNEL_IN_MONO,
      encoding,
      bufferBytes,
    )

  private fun stopAndRelease(record: AudioRecord) {
    try {
      if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
        record.stop()
      }
    } catch (e: IllegalStateException) {
      Log.w(TAG, "Failed to stop AudioRecord", e)
    } finally {
      record.release()
    }
  }
}

@VisibleForTesting
internal fun FloatArray.calculateNormalizedVolume(): Float =
  AsrAmplitudeNormalizer.normalizePcmSamples(this)
