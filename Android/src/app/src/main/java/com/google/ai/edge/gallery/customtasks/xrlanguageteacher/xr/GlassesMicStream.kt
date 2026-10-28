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

package com.google.ai.edge.gallery.customtasks.xrlanguageteacher.xr

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Captures the glasses microphone and streams it through a pipe as raw 16 kHz mono 16-bit PCM, the
 * format expected by ML Kit `AudioSource.fromPfd`.
 *
 * The [AudioRecord] is built from the projected device context, so capture is attributed to the
 * glasses virtual device (where RECORD_AUDIO is device-aware). At most one capture is active.
 */
class GlassesMicStream(
  private val scope: CoroutineScope,
  private val ioDispatcher: CoroutineDispatcher,
) {

  /** One active capture: an [AudioRecord] pumping PCM into a pipe on [ioDispatcher]. */
  private class Capture(val record: AudioRecord, val writeEnd: ParcelFileDescriptor) {
    @Volatile var aborted = false
    var job: Job? = null
  }

  private val lock = Any()
  private var activeCapture: Capture? = null

  /**
   * Stops any active capture, starts a new one from [glassesContext], and returns the read end of
   * the pipe. Returns null (the caller should fall back to its default mic) when the glasses
   * RECORD_AUDIO grant is missing or recording fails to start.
   */
  @SuppressLint("MissingPermission")
  fun open(glassesContext: Context): ParcelFileDescriptor? {
    stop()
    if (
      glassesContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
        PackageManager.PERMISSION_GRANTED
    ) {
      Log.w(TAG, "RECORD_AUDIO is not granted for the glasses device")
      return null
    }
    val minBuf =
      AudioRecord.getMinBufferSize(
        SAMPLE_RATE_HZ,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
      )
    if (minBuf <= 0) {
      Log.w(TAG, "Invalid AudioRecord min buffer size $minBuf")
      return null
    }
    var record: AudioRecord? = null
    var pipe: Array<ParcelFileDescriptor>? = null
    try {
      val newRecord =
        AudioRecord.Builder()
          .setContext(glassesContext)
          .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
          .setAudioFormat(
            AudioFormat.Builder()
              .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
              .setSampleRate(SAMPLE_RATE_HZ)
              .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
              .build()
          )
          .setBufferSizeInBytes(maxOf(minBuf, CHUNK_BYTES * 8))
          .build()
      record = newRecord
      check(newRecord.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord not initialized" }
      pipe = ParcelFileDescriptor.createPipe()
      newRecord.startRecording()
      check(newRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
        "AudioRecord failed to start (recordingState=${newRecord.recordingState})"
      }
    } catch (e: Exception) {
      Log.w(TAG, "Failed to start the glasses mic", e)
      runCatching { record?.release() }
      if (pipe != null) {
        for (fd in pipe) {
          runCatching { fd.close() }
        }
      }
      return null
    }
    val startedRecord = record ?: return null
    val (readEnd, writeEnd) = pipe ?: return null
    val capture = Capture(startedRecord, writeEnd)
    synchronized(lock) { activeCapture = capture }
    capture.job = scope.launch(ioDispatcher) { pump(capture) }
    Log.i(TAG, "Glasses mic started (routedDevice=${startedRecord.routedDevice?.productName})")
    return readEnd
  }

  /**
   * Stops hardware microphone capture immediately while flushing all already-recorded PCM bytes in
   * [AudioRecord]'s buffer into the ASR pipe before closing the pipe with EOF.
   */
  suspend fun finishAndFlush() {
    val capture = synchronized(lock) { activeCapture.also { activeCapture = null } } ?: return
    runCatching { capture.record.stop() }
    withTimeoutOrNull(FLUSH_TIMEOUT_MS) { capture.job?.join() }
    capture.aborted = true
    capture.job?.cancel()
    runCatching { capture.writeEnd.close() }
    Log.i(TAG, "Glasses mic flushed and closed")
  }

  /** Immediately aborts the active capture, discarding any remaining buffered audio. */
  fun stop() {
    val capture = synchronized(lock) { activeCapture.also { activeCapture = null } } ?: return
    capture.aborted = true
    capture.job?.cancel()
    // stop() unblocks a pending AudioRecord.read() so the pump coroutine exits promptly and cleans
    // up resources in pump()'s finally block without blocking the calling (e.g. main) thread.
    runCatching { capture.record.stop() }
    Log.i(TAG, "Glasses mic stopped")
  }

  /**
   * Continuously reads PCM chunks from [Capture.record] and writes them to [Capture.writeEnd] until
   * stopped, aborted, or EOF, then releases the [AudioRecord] and pipe write descriptor.
   */
  private suspend fun pump(capture: Capture) {
    val buffer = ByteArray(CHUNK_BYTES)
    try {
      ParcelFileDescriptor.AutoCloseOutputStream(capture.writeEnd).use { out ->
        while (!capture.aborted && currentCoroutineContext().isActive) {
          val read = capture.record.read(buffer, 0, buffer.size)
          if (read <= 0) {
            if (read < 0) {
              Log.w(TAG, "Glasses mic read error: $read")
            }
            break
          }
          out.write(buffer, 0, read)
        }
      }
    } catch (_: IOException) {
      // The reader (recognizer) closed its end; nothing more to deliver.
    } catch (e: Exception) {
      Log.w(TAG, "Glasses mic pump failed", e)
    } finally {
      runCatching { capture.record.stop() }
      runCatching { capture.record.release() }
      runCatching { capture.writeEnd.close() }
      synchronized(lock) { if (activeCapture === capture) activeCapture = null }
    }
  }

  private companion object {
    const val TAG = "AGGlassesMicStream"
    const val SAMPLE_RATE_HZ = 16000
    /** 20 ms of 16 kHz mono PCM16. */
    const val CHUNK_BYTES = 640
    const val FLUSH_TIMEOUT_MS = 1_000L
  }
}
