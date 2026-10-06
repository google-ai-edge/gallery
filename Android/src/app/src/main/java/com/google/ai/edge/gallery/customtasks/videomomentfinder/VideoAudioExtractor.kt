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

package com.google.ai.edge.gallery.customtasks.videomomentfinder

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import com.google.ai.edge.gallery.services.semanticretrieval.AUDIO_SAMPLE_RATE_HZ
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive

private const val TAG = "AGVideoAudioExtractor"

/** How long a single dequeue call waits for the decoder before the caller pumps it again. */
private const val DEQUEUE_TIMEOUT_US = 10_000L

/**
 * Maximum number of consecutive `INFO_TRY_AGAIN_LATER` output dequeues tolerated after the
 * end-of-stream input buffer has been queued (~1 second at [DEQUEUE_TIMEOUT_US]).
 */
internal const val MAX_POST_EOS_OUTPUT_TIMEOUTS = 100

/** Initial capacity of the rolling decoded sample buffer, in mono samples. */
private const val INITIAL_BUFFER_SAMPLES = 1 shl 15

/**
 * Decodes the audio track of a video file into 16 kHz mono PCM, one time window at a time.
 *
 * The track is decoded in a single forward pass: [readWindow] pumps the decoder only until the
 * requested window is covered and keeps a rolling buffer of the samples it has not consumed yet.
 * Seeking the decoder per window would be both slower and less reliable, so windows must be
 * requested in non-decreasing start order; a request that reaches back before the rolling buffer
 * returns null instead of silently returning the wrong audio.
 *
 * The ordering contract is over *successive calls*, not over the caller's notion of a window: each
 * call releases every sample before its own start. Overlapping windows are therefore fine as long
 * as each is read with a single call, but subdividing a window into several smaller calls is not,
 * because the next window would start behind the last subdivision of the previous one.
 *
 * This class is not thread safe and expects to be driven from a single IO coroutine. Callers must
 * [close] it to release the underlying codec and extractor.
 */
class VideoAudioExtractor
private constructor(
  private val extractor: MediaExtractor,
  private val codec: MediaCodec,
  sourceSampleRateHz: Int,
  sourceChannelCount: Int,
) : AutoCloseable {

  /**
   * Sample rate and channel count of the decoded PCM.
   *
   * Seeded from the container's track format and refreshed from the decoder's output format, which
   * is authoritative and always arrives before the first decoded buffer.
   */
  private var sampleRateHz: Int = sourceSampleRateHz

  private var channelCount: Int = sourceChannelCount

  private val decodedSamples = MonoPcmBuffer()

  private var inputDone = false
  private var outputDone = false
  private var outputFormatResolved = false
  private var postEosOutputTimeouts = 0
  private var closed = false

  /** Set once the audio is unusable, after which every [readWindow] returns null. */
  private var failed = false

  /**
   * Returns the audio between [startTimeMs] and [endTimeMs] as 16 kHz mono PCM, or null when the
   * window holds no usable audio (past the end of the track, requested out of order, or decoding
   * failed).
   */
  suspend fun readWindow(startTimeMs: Long, endTimeMs: Long): ShortArray? {
    if (closed || failed || endTimeMs <= startTimeMs) return null

    try {
      // Resolved first so the window boundaries below are computed against the decoder's real
      // sample rate rather than the container's, which can disagree.
      resolveOutputFormat()
      if (failed) return null

      val startIndex = sampleIndexAt(startTimeMs)
      val endIndex = sampleIndexAt(endTimeMs)
      if (startIndex < decodedSamples.firstSampleIndex) {
        Log.w(
          TAG,
          "Window [${startTimeMs}ms, ${endTimeMs}ms] starts before the decoded buffer;" +
            " windows must be requested in non-decreasing order",
        )
        return null
      }

      while (!outputDone && decodedSamples.endSampleIndex < endIndex) {
        coroutineContext.ensureActive()
        pumpOnce()
      }

      decodedSamples.dropBefore(startIndex)
      val availableEndIndex = minOf(endIndex, decodedSamples.endSampleIndex)
      if (availableEndIndex <= startIndex) return null

      val windowSamples = decodedSamples.slice(startIndex, availableEndIndex)
      return resampleLinear(windowSamples, sampleRateHz, AUDIO_SAMPLE_RATE_HZ).takeIf {
        it.isNotEmpty()
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // Decoding is best effort: a broken audio track must degrade indexing to frames only rather
      // than fail the whole pass.
      Log.e(TAG, "Failed to decode audio for window [${startTimeMs}ms, ${endTimeMs}ms]", e)
      failed = true
      return null
    }
  }

  override fun close() {
    if (closed) return
    closed = true
    try {
      codec.stop()
    } catch (e: Exception) {
      Log.w(TAG, "Error stopping the audio decoder: ${e.message}")
    }
    try {
      codec.release()
    } catch (e: Exception) {
      Log.w(TAG, "Error releasing the audio decoder: ${e.message}")
    }
    try {
      extractor.release()
    } catch (e: Exception) {
      Log.w(TAG, "Error releasing the audio extractor: ${e.message}")
    }
  }

  /** Index, in decoded mono samples, of the sample playing at [timeMs]. */
  private fun sampleIndexAt(timeMs: Long): Long = timeMs.coerceAtLeast(0L) * sampleRateHz / 1000L

  /**
   * Pumps the decoder until its output format is known.
   *
   * Bounded by the arrival of the first decoded buffer, so a decoder that never reports a format
   * change simply keeps the container's values instead of decoding the whole track.
   */
  private suspend fun resolveOutputFormat() {
    while (!outputFormatResolved && !outputDone) {
      coroutineContext.ensureActive()
      pumpOnce()
    }
  }

  /** Feeds at most one input buffer to the decoder and drains at most one output buffer. */
  private fun pumpOnce() {
    if (!inputDone) {
      val inputIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
      if (inputIndex >= 0) {
        val inputBuffer = codec.getInputBuffer(inputIndex)
        val sampleSize = if (inputBuffer == null) -1 else extractor.readSampleData(inputBuffer, 0)
        if (sampleSize < 0) {
          codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
          inputDone = true
        } else {
          codec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
          val unused = extractor.advance()
        }
      }
    }

    val bufferInfo = MediaCodec.BufferInfo()
    val outputIndex = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
    if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
      postEosOutputTimeouts = 0
      readOutputFormat(codec.outputFormat)
      return
    }
    if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
      if (inputDone) {
        postEosOutputTimeouts++
        if (postEosOutputTimeouts >= MAX_POST_EOS_OUTPUT_TIMEOUTS) {
          Log.w(
            TAG,
            "Audio decoder timed out $postEosOutputTimeouts consecutive times after input EOS" +
              " without emitting output EOS; marking output done",
          )
          outputDone = true
        }
      }
      return
    }
    if (outputIndex < 0) {
      postEosOutputTimeouts = 0
      return
    }

    postEosOutputTimeouts = 0
    val outputBuffer = codec.getOutputBuffer(outputIndex)
    if (outputBuffer != null && bufferInfo.size > 0) {
      outputFormatResolved = true
      if (!failed) {
        outputBuffer.position(bufferInfo.offset)
        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
        appendDecoded(outputBuffer)
      }
    }
    codec.releaseOutputBuffer(outputIndex, /* render= */ false)
    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
      outputDone = true
    }
  }

  private fun readOutputFormat(format: MediaFormat) {
    outputFormatResolved = true
    if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
      val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
      if (rate > 0) {
        sampleRateHz = rate
      }
    }
    if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
      val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
      if (channels > 0) {
        channelCount = channels
      }
    }
    val encoding =
      if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
        format.getInteger(MediaFormat.KEY_PCM_ENCODING)
      } else {
        AudioFormat.ENCODING_PCM_16BIT
      }
    if (encoding != AudioFormat.ENCODING_PCM_16BIT) {
      // Reading a float or 8-bit stream as 16-bit PCM would produce noise, which is worse for
      // retrieval than having no audio at all.
      Log.w(TAG, "Unsupported decoder PCM encoding $encoding; skipping audio for this video")
      failed = true
    }
  }

  private fun appendDecoded(buffer: ByteBuffer) {
    val shortBuffer = buffer.order(ByteOrder.nativeOrder()).asShortBuffer()
    val available = shortBuffer.remaining()
    if (available < channelCount) return
    val interleaved = ShortArray(available - available % channelCount)
    shortBuffer.get(interleaved)
    decodedSamples.append(downmixToMono(interleaved, channelCount))
  }

  companion object {
    /**
     * Opens the audio track of [videoPath], or returns null when the file has no audio track, no
     * usable decoder, or cannot be read at all. A silent video is an ordinary case, not an error.
     */
    fun createOrNull(videoPath: String): VideoAudioExtractor? {
      var extractor: MediaExtractor? = null
      var codec: MediaCodec? = null
      try {
        val mediaExtractor = MediaExtractor().apply { setDataSource(videoPath) }
        extractor = mediaExtractor

        var trackIndex = -1
        var trackMime = ""
        for (i in 0 until mediaExtractor.trackCount) {
          val mime = mediaExtractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
          if (mime.startsWith("audio/")) {
            trackIndex = i
            trackMime = mime
            break
          }
        }
        if (trackIndex < 0) {
          Log.i(TAG, "No audio track in the video; indexing frames only")
          mediaExtractor.release()
          return null
        }

        val format = mediaExtractor.getTrackFormat(trackIndex)
        val sampleRate =
          if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
            format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
          } else {
            0
          }
        val channels =
          if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
            format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
          } else {
            0
          }
        if (sampleRate <= 0 || channels <= 0) {
          Log.w(TAG, "Audio track declares no sample rate or channel count; skipping audio")
          mediaExtractor.release()
          return null
        }

        mediaExtractor.selectTrack(trackIndex)
        val decoder = MediaCodec.createDecoderByType(trackMime)
        codec = decoder
        decoder.configure(format, /* surface= */ null, /* crypto= */ null, /* flags= */ 0)
        decoder.start()
        return VideoAudioExtractor(mediaExtractor, decoder, sampleRate, channels)
      } catch (e: Exception) {
        Log.w(TAG, "Failed to open the audio track of the video; indexing frames only", e)
        try {
          codec?.release()
        } catch (releaseError: Exception) {
          Log.w(TAG, "Error releasing the audio decoder: ${releaseError.message}")
        }
        try {
          extractor?.release()
        } catch (releaseError: Exception) {
          Log.w(TAG, "Error releasing the audio extractor: ${releaseError.message}")
        }
        return null
      }
    }
  }
}

/**
 * Averages the channels of [interleaved] into a single mono channel.
 *
 * The embedding model's frontend is mono only, and averaging (rather than dropping the extra
 * channels) keeps content that was panned to one side.
 */
internal fun downmixToMono(interleaved: ShortArray, channelCount: Int): ShortArray {
  require(channelCount > 0) { "channelCount must be positive, got $channelCount" }
  if (channelCount == 1) return interleaved
  val frameCount = interleaved.size / channelCount
  val mono = ShortArray(frameCount)
  for (frame in 0 until frameCount) {
    val base = frame * channelCount
    var sum = 0
    for (channel in 0 until channelCount) {
      sum += interleaved[base + channel]
    }
    mono[frame] = (sum / channelCount).toShort()
  }
  return mono
}

/**
 * Resamples mono [input] from [inputRateHz] to [outputRateHz] by linear interpolation.
 *
 * Unlike the microphone path's helper this resamples in both directions: video tracks are usually
 * 44.1 or 48 kHz, and leaving them untouched would feed the mel frontend time-compressed audio.
 */
internal fun resampleLinear(input: ShortArray, inputRateHz: Int, outputRateHz: Int): ShortArray {
  require(inputRateHz > 0) { "inputRateHz must be positive, got $inputRateHz" }
  require(outputRateHz > 0) { "outputRateHz must be positive, got $outputRateHz" }
  if (input.isEmpty() || inputRateHz == outputRateHz) return input

  val outputSize = (input.size.toLong() * outputRateHz / inputRateHz).toInt()
  if (outputSize <= 0) return ShortArray(0)

  val output = ShortArray(outputSize)
  val step = inputRateHz.toDouble() / outputRateHz.toDouble()
  for (i in output.indices) {
    val sourcePosition = i * step
    val lowerIndex = sourcePosition.toInt()
    val fraction = sourcePosition - lowerIndex
    val lower = input[lowerIndex].toDouble()
    val upper = if (lowerIndex + 1 < input.size) input[lowerIndex + 1].toDouble() else lower
    output[i] =
      (lower + (upper - lower) * fraction)
        .roundToInt()
        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        .toShort()
  }
  return output
}

/**
 * Growable buffer of decoded mono samples that remembers the absolute index of its first sample.
 *
 * Absolute indices let [VideoAudioExtractor] address a window by its position in the whole track
 * while only holding the samples between the current window and the decoder's position.
 */
private class MonoPcmBuffer {
  private var data = ShortArray(INITIAL_BUFFER_SAMPLES)
  private var size = 0

  /** Absolute index, within the track, of the first sample still buffered. */
  var firstSampleIndex: Long = 0L
    private set

  /** Absolute index one past the last buffered sample. */
  val endSampleIndex: Long
    get() = firstSampleIndex + size

  fun append(samples: ShortArray) {
    if (samples.isEmpty()) return
    if (size + samples.size > data.size) {
      var newCapacity = maxOf(data.size, 1)
      while (newCapacity < size + samples.size) {
        newCapacity *= 2
      }
      data = data.copyOf(newCapacity)
    }
    samples.copyInto(data, size)
    size += samples.size
  }

  /** Discards every sample before absolute index [sampleIndex]. */
  fun dropBefore(sampleIndex: Long) {
    val dropCount = (sampleIndex - firstSampleIndex).coerceIn(0L, size.toLong()).toInt()
    if (dropCount == 0) return
    data.copyInto(data, 0, dropCount, size)
    size -= dropCount
    firstSampleIndex += dropCount
  }

  /** Returns the buffered samples in the absolute index range [fromIndex], [toIndex]). */
  fun slice(fromIndex: Long, toIndex: Long): ShortArray {
    val from = (fromIndex - firstSampleIndex).coerceIn(0L, size.toLong()).toInt()
    val to = (toIndex - firstSampleIndex).coerceIn(from.toLong(), size.toLong()).toInt()
    return data.copyOfRange(from, to)
  }
}
