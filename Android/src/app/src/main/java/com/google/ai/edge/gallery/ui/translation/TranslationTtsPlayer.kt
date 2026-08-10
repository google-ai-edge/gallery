/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.ui.translation

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.google.ai.edge.gallery.BuildConfig
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

private const val TAG = "AGTranslationTtsPlayer"
private const val PLAYBACK_POLL_INTERVAL_MILLIS = 10L
private const val PLAYBACK_COMPLETION_GRACE_MILLIS = 2000L
private const val TEXT_QUEUE_CAPACITY = 8
private const val PCM_QUEUE_CAPACITY = 4

internal data class TranslationTtsStreamingResult(
  val queuedChunkCount: Int,
  val playedChunkCount: Int,
  val error: Throwable?,
  val cancelled: Boolean,
)

internal class TranslationTtsPlayer(
  context: Context,
  private val model: TranslationTtsModel = TranslationTtsModel.DEFAULT,
  private val engine: TranslationTtsEngine? =
    if (model == TranslationTtsModel.SYSTEM) null
    else TranslationTtsEngineStore.get(context.applicationContext, model),
  private val systemFallback: TranslationSystemTtsFallback =
    AndroidTranslationSystemTtsFallback(context.applicationContext),
  private val sherpaEnabled: Boolean = BuildConfig.TRANSLATION_TTS_SHERPA_ENABLED,
) {
  private val playerJob = SupervisorJob()
  private val sessionId = AtomicLong(0L)
  private val sessionLock = Any()
  private val audioTrackLock = Any()
  private val playbackGeneration = AtomicLong(0L)
  private val _isSpeaking = MutableStateFlow(false)
  val isSpeaking = _isSpeaking.asStateFlow()
  private var currentAudioTrack: AudioTrack? = null
  private var streamingSession: StreamingSession? = null

  suspend fun preload() {
    if (sherpaEnabled) engine?.preload()
  }

  suspend fun speak(
    text: String,
    languageTag: String,
    preferSherpa: Boolean = true,
  ) {
    val trimmedText = text.trim()
    if (trimmedText.isEmpty()) {
      Log.i(
        TAG,
        "backend=${model.backendId} revision=${model.revision} " +
          "language=${SherpaKokoroVoiceSelector.normalize(languageTag)} outcome=skipped_blank",
      )
      return
    }

    stop()
    _isSpeaking.value = true
    try {
      val sherpaEngine = engine
      if (sherpaEnabled && preferSherpa && sherpaEngine != null) {
        try {
          val audio = sherpaEngine.synthesize(text = trimmedText, languageTag = languageTag)
          playAndAwait(audio = audio, languageTag = languageTag)
          return
        } catch (exception: CancellationException) {
          throw exception
        } catch (_: Throwable) {
          Log.w(
            TAG,
            "backend=${model.backendId} revision=${model.revision} " +
              "language=${SherpaKokoroVoiceSelector.normalize(languageTag)} " +
              "outcome=fallback_started",
          )
        }
      }
      systemFallback.speak(text = trimmedText, languageTag = languageTag)
    } finally {
      _isSpeaking.value = false
    }
  }

  fun startStreaming(languageTag: String): Long {
    stop()

    val id = sessionId.incrementAndGet()
    val sessionJob = SupervisorJob(playerJob)
    val session = StreamingSession(id = id, languageTag = languageTag, job = sessionJob)
    synchronized(sessionLock) { streamingSession = session }
    val sessionScope = CoroutineScope(sessionJob + Dispatchers.IO)
    sessionScope.launch { runSynthesisQueue(session = session) }
    sessionScope.launch { runPlaybackQueue(session = session) }
    Log.i(
      TAG,
      "backend=${model.backendId} revision=${model.revision} " +
        "language=${SherpaKokoroVoiceSelector.normalize(languageTag)} outcome=stream_started",
    )
    return id
  }

  suspend fun enqueueStreaming(sessionId: Long, text: String): Boolean {
    val trimmedText = text.trim()
    if (trimmedText.isEmpty()) return false

    val session = synchronized(sessionLock) { streamingSession?.takeIf { it.id == sessionId } }
      ?: return false
    return try {
      session.textChunks.send(trimmedText)
      _isSpeaking.value = true
      session.queuedChunkCount.incrementAndGet()
      true
    } catch (_: CancellationException) {
      currentCoroutineContext().ensureActive()
      false
    } catch (_: ClosedSendChannelException) {
      false
    }
  }

  suspend fun finishStreaming(sessionId: Long): TranslationTtsStreamingResult {
    val session = synchronized(sessionLock) { streamingSession?.takeIf { it.id == sessionId } }
      ?: run {
        _isSpeaking.value = false
        return TranslationTtsStreamingResult(0, 0, null, cancelled = true)
      }
    return try {
      session.textChunks.close()
      val result = session.completion.await()
      synchronized(sessionLock) {
        if (streamingSession === session) streamingSession = null
      }
      session.job.cancel()
      result
    } finally {
      _isSpeaking.value = false
    }
  }

  fun stop() {
    val session = synchronized(sessionLock) { streamingSession.also { streamingSession = null } }
    session?.textChunks?.cancel()
    session?.playbackItems?.cancel()
    session?.job?.cancel()
    stopAudioTrack()
    systemFallback.stop()
    _isSpeaking.value = false
  }

  fun release() {
    stop()
    playerJob.cancel()
    systemFallback.release()
  }

  private suspend fun runSynthesisQueue(session: StreamingSession) {
    val sherpaEngine = engine
    var useSherpa = sherpaEnabled && sherpaEngine != null
    try {
      for (text in session.textChunks) {
        currentCoroutineContext().ensureActive()
        if (!useSherpa) {
          session.playbackItems.send(TranslationTtsPlaybackItem.SystemSpeech(text))
          continue
        }

        var emittedPcm = false
        try {
          val audio =
            checkNotNull(sherpaEngine).synthesizeStreaming(
              text = text,
              languageTag = session.languageTag,
              onPcmChunk = { pcmChunk ->
                emittedPcm = true
                session.job.isActive &&
                  session.playbackItems.trySendBlocking(
                    TranslationTtsPlaybackItem.PcmChunk(pcmChunk)
                  )
                    .isSuccess
              },
            )
          currentCoroutineContext().ensureActive()
          session.playbackItems.send(
            TranslationTtsPlaybackItem.PcmEnd(generatedSampleCount = audio.samples.size)
          )
        } catch (exception: CancellationException) {
          throw exception
        } catch (throwable: Throwable) {
          useSherpa = false
          Log.w(
            TAG,
            "backend=${model.backendId} revision=${model.revision} " +
              "language=${SherpaKokoroVoiceSelector.normalize(session.languageTag)} outcome=" +
              if (emittedPcm) "native_stream_failed_after_pcm" else "fallback_started",
            throwable,
          )
          if (emittedPcm) {
            session.playbackItems.send(TranslationTtsPlaybackItem.PcmAbort)
          } else {
            session.playbackItems.send(TranslationTtsPlaybackItem.SystemSpeech(text))
          }
        }
      }
    } catch (exception: CancellationException) {
      throw exception
    } catch (throwable: Throwable) {
      session.error.compareAndSet(null, throwable)
      session.textChunks.cancel()
      Log.w(
        TAG,
        "backend=${model.backendId} revision=${model.revision} " +
          "language=${SherpaKokoroVoiceSelector.normalize(session.languageTag)} " +
          "outcome=synthesis_failed",
      )
    } finally {
      session.playbackItems.close()
    }
  }

  private suspend fun runPlaybackQueue(session: StreamingSession) {
    var nativePlayback: NativePcmPlayback? = null
    try {
      for (playbackItem in session.playbackItems) {
        when (playbackItem) {
          is TranslationTtsPlaybackItem.PcmChunk -> {
            val activePlayback =
              nativePlayback
                ?: startNativePcmPlayback(
                    firstChunk = playbackItem.audio,
                    languageTag = session.languageTag,
                  )
                  .also { nativePlayback = it }
            if (playbackItem.audio.sampleRate != activePlayback.sampleRate) {
              throw IOException(
                "Translation PCM sample rate changed from ${activePlayback.sampleRate} to " +
                  "${playbackItem.audio.sampleRate}."
              )
            }
            writeNativePcmChunk(playback = activePlayback, samples = playbackItem.audio.samples)
          }
          is TranslationTtsPlaybackItem.PcmEnd -> {
            val activePlayback = nativePlayback
            val callbackSampleCount =
              if (activePlayback == null) {
                0L
              } else {
                activePlayback.samplesWritten - activePlayback.validatedSamples
              }
            if (callbackSampleCount != playbackItem.generatedSampleCount.toLong()) {
              Log.w(
                TAG,
                "backend=${model.backendId} revision=${model.revision} " +
                  "language=${SherpaKokoroVoiceSelector.normalize(session.languageTag)} " +
                  "outcome=native_stream_sample_count_mismatch callback_samples=" +
                  "$callbackSampleCount generated_samples=${playbackItem.generatedSampleCount}",
              )
            }
            activePlayback?.validatedSamples = activePlayback.samplesWritten
            session.playedChunkCount.incrementAndGet()
          }
          TranslationTtsPlaybackItem.PcmAbort -> {
            nativePlayback?.let { it.validatedSamples = it.samplesWritten }
            session.playedChunkCount.incrementAndGet()
          }
          is TranslationTtsPlaybackItem.SystemSpeech -> {
            nativePlayback?.let {
              finishNativePcmPlayback(playback = it, languageTag = session.languageTag)
              nativePlayback = null
            }
            systemFallback.speak(text = playbackItem.text, languageTag = session.languageTag)
            session.playedChunkCount.incrementAndGet()
          }
        }
      }
      nativePlayback?.let {
        finishNativePcmPlayback(playback = it, languageTag = session.languageTag)
        nativePlayback = null
      }
    } catch (exception: CancellationException) {
      throw exception
    } catch (throwable: Throwable) {
      session.error.compareAndSet(null, throwable)
      session.textChunks.cancel()
      session.playbackItems.cancel()
      Log.w(
        TAG,
        "backend=${model.backendId} revision=${model.revision} " +
          "language=${SherpaKokoroVoiceSelector.normalize(session.languageTag)} " +
          "outcome=playback_failed",
      )
    } finally {
      nativePlayback?.let { releaseAudioTrack(it.audioTrack) }
      while (session.playbackItems.tryReceive().getOrNull() != null) {}
      session.completion.complete(
        TranslationTtsStreamingResult(
          queuedChunkCount = session.queuedChunkCount.get(),
          playedChunkCount = session.playedChunkCount.get(),
          error = session.error.get(),
          cancelled = session.job.isCancelled,
        )
      )
    }
  }

  private suspend fun playAndAwait(audio: SynthesizedAudio, languageTag: String) =
    withContext(Dispatchers.IO) {
      val normalizedLanguageTag = SherpaKokoroVoiceSelector.normalize(languageTag)
      val metrics =
        try {
          TranslationTtsAudioValidator.validate(audio)
        } catch (exception: TranslationTtsSynthesisException) {
          logPlaybackOutcome(
            languageTag = normalizedLanguageTag,
            audio = audio,
            metrics = exception.metrics,
            outcome = "alert_${exception.audioFailure?.name?.lowercase() ?: "validation_failed"}",
            alert = exception.audioFailure != null,
          )
          throw exception
        }
      val audioTrack = createAudioTrack(audio = audio)
      val generation = registerAudioTrack(audioTrack)

      try {
        audioTrack.play()
        logPlaybackOutcome(
          languageTag = normalizedLanguageTag,
          audio = audio,
          metrics = metrics,
          outcome = "playback_started",
        )
        var offset = 0
        while (offset < audio.samples.size) {
          currentCoroutineContext().ensureActive()
          ensureCurrentPlayback(generation)
          val written =
            audioTrack.write(
              audio.samples,
              offset,
              audio.samples.size - offset,
              AudioTrack.WRITE_BLOCKING,
            )
          if (written < 0) {
            throw IOException("AudioTrack write failed with error $written.")
          }
          if (written == 0) {
            delay(PLAYBACK_POLL_INTERVAL_MILLIS)
          } else {
            offset += written
          }
        }

        val timeoutMillis =
          max(
            PLAYBACK_COMPLETION_GRACE_MILLIS,
            (metrics.durationSeconds * 1000.0).toLong() + PLAYBACK_COMPLETION_GRACE_MILLIS,
          )
        withTimeout(timeoutMillis) {
          while (unsignedPlaybackHead(audioTrack) < audio.samples.size.toLong()) {
            currentCoroutineContext().ensureActive()
            ensureCurrentPlayback(generation)
            delay(PLAYBACK_POLL_INTERVAL_MILLIS)
          }
        }
        logPlaybackOutcome(
          languageTag = normalizedLanguageTag,
          audio = audio,
          metrics = metrics,
          outcome = "playback_completed",
        )
      } catch (exception: CancellationException) {
        logPlaybackOutcome(
          languageTag = normalizedLanguageTag,
          audio = audio,
          metrics = metrics,
          outcome = "playback_cancelled",
        )
        throw exception
      } catch (throwable: Throwable) {
        logPlaybackOutcome(
          languageTag = normalizedLanguageTag,
          audio = audio,
          metrics = metrics,
          outcome = "playback_failed",
          alert = true,
        )
        throw throwable
      } finally {
        releaseAudioTrack(audioTrack)
      }
    }

  private fun createAudioTrack(audio: SynthesizedAudio): AudioTrack {
    return createAudioTrack(sampleRate = audio.sampleRate)
  }

  private fun createAudioTrack(sampleRate: Int): AudioTrack {
    val channelMask = AudioFormat.CHANNEL_OUT_MONO
    val encoding = AudioFormat.ENCODING_PCM_FLOAT
    val minimumBufferSize =
      AudioTrack.getMinBufferSize(sampleRate, channelMask, encoding)
    if (minimumBufferSize <= 0) {
      throw IOException("Unable to create AudioTrack buffer: error=$minimumBufferSize")
    }
    val audioTrack =
      AudioTrack.Builder()
        .setAudioAttributes(
          AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .build()
        )
        .setAudioFormat(
          AudioFormat.Builder()
            .setEncoding(encoding)
            .setSampleRate(sampleRate)
            .setChannelMask(channelMask)
            .build()
        )
        .setBufferSizeInBytes(max(minimumBufferSize, 4096))
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()
    if (audioTrack.state != AudioTrack.STATE_INITIALIZED) {
      audioTrack.release()
      throw IOException("Unable to initialize AudioTrack for Translation TTS.")
    }
    return audioTrack
  }

  private fun startNativePcmPlayback(
    firstChunk: SynthesizedAudio,
    languageTag: String,
  ): NativePcmPlayback {
    if (firstChunk.sampleRate <= 0) {
      throw IOException("Translation native PCM callback returned an invalid sample rate.")
    }
    val audioTrack = createAudioTrack(sampleRate = firstChunk.sampleRate)
    val generation = registerAudioTrack(audioTrack)
    audioTrack.play()
    Log.i(
      TAG,
      "backend=${model.backendId} revision=${model.revision} " +
        "language=${SherpaKokoroVoiceSelector.normalize(languageTag)} " +
        "sample_rate=${firstChunk.sampleRate} first_chunk_samples=${firstChunk.samples.size} " +
        "outcome=native_stream_playback_started",
    )
    return NativePcmPlayback(
      audioTrack = audioTrack,
      generation = generation,
      sampleRate = firstChunk.sampleRate,
    )
  }

  private suspend fun writeNativePcmChunk(
    playback: NativePcmPlayback,
    samples: FloatArray,
  ) {
    var offset = 0
    while (offset < samples.size) {
      currentCoroutineContext().ensureActive()
      ensureCurrentPlayback(playback.generation)
      val written =
        playback.audioTrack.write(
          samples,
          offset,
          samples.size - offset,
          AudioTrack.WRITE_BLOCKING,
        )
      if (written < 0) throw IOException("AudioTrack write failed with error $written.")
      if (written == 0) {
        delay(PLAYBACK_POLL_INTERVAL_MILLIS)
      } else {
        offset += written
        playback.samplesWritten += written
      }
    }
  }

  private suspend fun finishNativePcmPlayback(
    playback: NativePcmPlayback,
    languageTag: String,
  ) {
    val durationSeconds = playback.samplesWritten.toDouble() / playback.sampleRate
    val timeoutMillis =
      max(
        PLAYBACK_COMPLETION_GRACE_MILLIS,
        (durationSeconds * 1000.0).toLong() + PLAYBACK_COMPLETION_GRACE_MILLIS,
      )
    try {
      withTimeout(timeoutMillis) {
        while (unsignedPlaybackHead(playback.audioTrack) < playback.samplesWritten) {
          currentCoroutineContext().ensureActive()
          ensureCurrentPlayback(playback.generation)
          delay(PLAYBACK_POLL_INTERVAL_MILLIS)
        }
      }
      Log.i(
        TAG,
        "backend=${model.backendId} revision=${model.revision} " +
          "language=${SherpaKokoroVoiceSelector.normalize(languageTag)} " +
          "sample_count=${playback.samplesWritten} duration=$durationSeconds " +
          "outcome=native_stream_playback_completed",
      )
    } finally {
      releaseAudioTrack(playback.audioTrack)
    }
  }

  private fun registerAudioTrack(audioTrack: AudioTrack): Long {
    val previousTrack: AudioTrack?
    val generation: Long
    synchronized(audioTrackLock) {
      previousTrack = currentAudioTrack
      currentAudioTrack = audioTrack
      generation = playbackGeneration.incrementAndGet()
    }
    previousTrack?.let(::stopAndRelease)
    return generation
  }

  private fun stopAudioTrack() {
    val audioTrack: AudioTrack?
    synchronized(audioTrackLock) {
      playbackGeneration.incrementAndGet()
      audioTrack = currentAudioTrack
      currentAudioTrack = null
    }
    audioTrack?.let(::stopAndRelease)
  }

  private fun releaseAudioTrack(audioTrack: AudioTrack) {
    val shouldRelease =
      synchronized(audioTrackLock) {
        if (currentAudioTrack === audioTrack) {
          currentAudioTrack = null
          true
        } else {
          false
        }
      }
    if (shouldRelease) {
      stopAndRelease(audioTrack)
    }
  }

  private fun stopAndRelease(audioTrack: AudioTrack) {
    try {
      if (audioTrack.playState == AudioTrack.PLAYSTATE_PLAYING) audioTrack.stop()
      audioTrack.flush()
    } catch (_: IllegalStateException) {
    } finally {
      audioTrack.release()
    }
  }

  private fun ensureCurrentPlayback(generation: Long) {
    if (playbackGeneration.get() != generation) {
      throw CancellationException("Translation PCM playback was stopped.")
    }
  }

  private fun unsignedPlaybackHead(audioTrack: AudioTrack): Long =
    audioTrack.playbackHeadPosition.toLong() and 0xffffffffL

  private fun logPlaybackOutcome(
    languageTag: String,
    audio: SynthesizedAudio,
    metrics: TranslationTtsAudioMetrics?,
    outcome: String,
    alert: Boolean = false,
  ) {
    val message =
      "backend=${model.backendId} revision=${model.revision} " +
        "language=$languageTag sample_count=${audio.samples.size} " +
        "duration=${metrics?.durationSeconds ?: 0.0} rms=${metrics?.rms ?: 0.0} " +
        "outcome=$outcome"
    if (alert) Log.e(TAG, "ALERT $message") else Log.i(TAG, message)
  }

  private class StreamingSession(
    val id: Long,
    val languageTag: String,
    val job: Job,
    val textChunks: Channel<String> = Channel(TEXT_QUEUE_CAPACITY),
    val playbackItems: Channel<TranslationTtsPlaybackItem> = Channel(PCM_QUEUE_CAPACITY),
    val queuedChunkCount: AtomicInteger = AtomicInteger(0),
    val playedChunkCount: AtomicInteger = AtomicInteger(0),
    val error: AtomicReference<Throwable?> = AtomicReference(null),
    val completion: CompletableDeferred<TranslationTtsStreamingResult> = CompletableDeferred(),
  )

  private data class NativePcmPlayback(
    val audioTrack: AudioTrack,
    val generation: Long,
    val sampleRate: Int,
    var samplesWritten: Long = 0L,
    var validatedSamples: Long = 0L,
  )

  private sealed interface TranslationTtsPlaybackItem {
    data class PcmChunk(val audio: SynthesizedAudio) : TranslationTtsPlaybackItem

    data class PcmEnd(val generatedSampleCount: Int) : TranslationTtsPlaybackItem

    data object PcmAbort : TranslationTtsPlaybackItem

    data class SystemSpeech(val text: String) : TranslationTtsPlaybackItem
  }
}
