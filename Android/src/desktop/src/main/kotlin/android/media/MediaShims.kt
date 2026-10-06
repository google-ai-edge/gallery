package android.media

class AudioFormat {
  class Builder {
    fun setEncoding(encoding: Int): Builder = this
    fun setSampleRate(sampleRate: Int): Builder = this
    fun setChannelMask(channelMask: Int): Builder = this
    fun build(): AudioFormat = AudioFormat()
  }

  companion object {
    const val CHANNEL_IN_MONO = 16
    const val CHANNEL_OUT_MONO = 4
    const val ENCODING_PCM_16BIT = 2
  }
}

object MediaRecorder {
  object AudioSource {
    const val MIC = 1
  }
}

class AudioRecord(
  val audioSource: Int = 0,
  val sampleRateInHz: Int = 16000,
  val channelConfig: Int = AudioFormat.CHANNEL_IN_MONO,
  val audioFormat: Int = AudioFormat.ENCODING_PCM_16BIT,
  val bufferSizeInBytes: Int = 1024
) {
  var recordingState: Int = RECORDSTATE_STOPPED
  private var targetDataLine: javax.sound.sampled.TargetDataLine? = null

  fun startRecording() {
    recordingState = RECORDSTATE_RECORDING
    try {
      val channels = if (channelConfig == AudioFormat.CHANNEL_IN_MONO) 1 else 2
      val sampleSizeInBits = if (audioFormat == AudioFormat.ENCODING_PCM_16BIT) 16 else 8
      val format = javax.sound.sampled.AudioFormat(sampleRateInHz.toFloat(), sampleSizeInBits, channels, true, false)
      val info = javax.sound.sampled.DataLine.Info(javax.sound.sampled.TargetDataLine::class.java, format)
      targetDataLine = javax.sound.sampled.AudioSystem.getLine(info) as javax.sound.sampled.TargetDataLine
      targetDataLine?.open(format)
      targetDataLine?.start()
    } catch (e: Exception) {
      e.printStackTrace()
    }
  }

  fun stop() {
    recordingState = RECORDSTATE_STOPPED
    targetDataLine?.stop()
  }

  fun release() {
    recordingState = RECORDSTATE_STOPPED
    targetDataLine?.stop()
    targetDataLine?.close()
    targetDataLine = null
  }

  fun read(audioData: ByteArray, offsetInBytes: Int, sizeInBytes: Int): Int {
    if (recordingState != RECORDSTATE_RECORDING || targetDataLine == null) {
      Thread.sleep(50)
      return 0
    }
    return targetDataLine?.read(audioData, offsetInBytes, sizeInBytes) ?: 0
  }

  companion object {
    const val RECORDSTATE_STOPPED = 1
    const val RECORDSTATE_RECORDING = 3

    @JvmStatic
    fun getMinBufferSize(sampleRateInHz: Int, channelConfig: Int, audioFormat: Int): Int = 4096
  }
}

class AudioAttributes {
  class Builder {
    fun setContentType(contentType: Int): Builder = this
    fun setUsage(usage: Int): Builder = this
    fun build(): AudioAttributes = AudioAttributes()
  }

  companion object {
    const val CONTENT_TYPE_SPEECH = 1
    const val USAGE_MEDIA = 1
  }
}

class AudioTrack {
  var playState: Int = PLAYSTATE_STOPPED
  private var sourceDataLine: javax.sound.sampled.SourceDataLine? = null

  fun play() {
    playState = PLAYSTATE_PLAYING
    try {
      if (sourceDataLine == null) {
        // Defaulting to 24000Hz mono 16-bit PCM for AI Edge Audio models
        val format = javax.sound.sampled.AudioFormat(24000f, 16, 1, true, false)
        val info = javax.sound.sampled.DataLine.Info(javax.sound.sampled.SourceDataLine::class.java, format)
        sourceDataLine = javax.sound.sampled.AudioSystem.getLine(info) as javax.sound.sampled.SourceDataLine
        sourceDataLine?.open(format)
      }
      sourceDataLine?.start()
    } catch (e: Exception) {
      e.printStackTrace()
    }
  }

  fun stop() {
    playState = PLAYSTATE_STOPPED
    sourceDataLine?.stop()
  }

  fun pause() {
    playState = PLAYSTATE_PAUSED
    sourceDataLine?.stop()
  }

  fun release() {
    playState = PLAYSTATE_STOPPED
    sourceDataLine?.stop()
    sourceDataLine?.close()
    sourceDataLine = null
  }

  fun write(audioData: ByteArray, offsetInBytes: Int, sizeInBytes: Int): Int {
    if (playState != PLAYSTATE_PLAYING || sourceDataLine == null) return 0
    return sourceDataLine?.write(audioData, offsetInBytes, sizeInBytes) ?: 0
  }
  val playbackHeadPosition: Int get() = sourceDataLine?.framePosition ?: 0

  class Builder {
    fun setAudioAttributes(attributes: AudioAttributes): Builder = this
    fun setAudioFormat(format: AudioFormat): Builder = this
    fun setTransferMode(mode: Int): Builder = this
    fun setBufferSizeInBytes(bytes: Int): Builder = this
    fun build(): AudioTrack = AudioTrack()
  }

  companion object {
    const val MODE_STATIC = 0
    const val MODE_STREAM = 1
    const val PLAYSTATE_STOPPED = 1
    const val PLAYSTATE_PAUSED = 2
    const val PLAYSTATE_PLAYING = 3
  }
}
