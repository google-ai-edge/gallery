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

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.util.Log
import androidx.annotation.VisibleForTesting
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

private const val TAG = "AGMp4Normalizer"

/** Size of a plain MP4 box header: a 32-bit size followed by a four character type. */
private const val BOX_HEADER_SIZE = 8L

/** Size of an MP4 box header that carries a 64-bit size, signalled by a 32-bit size of 1. */
private const val LARGE_BOX_HEADER_SIZE = 16L

/** `moof`, the box that heads a fragment in a fragmented MP4. */
private const val BOX_TYPE_MOOF = 0x6D6F6F66

/** Starting size of the sample copy buffer, grown on demand for large keyframes. */
private const val INITIAL_SAMPLE_BUFFER_SIZE = 1024 * 1024 // 1 MB

/** Upper bound for the sample copy buffer, well above a 4K keyframe. */
private const val MAX_SAMPLE_BUFFER_SIZE = 32 * 1024 * 1024 // 32 MB

/** Suffix of the scratch file the remuxed video is written to before it replaces the original. */
private const val NORMALIZED_FILE_SUFFIX = ".normalized.mp4"

/** Suffix of the copy the original is set aside under while the remuxed file takes its place. */
private const val BACKUP_FILE_SUFFIX = ".original"

/**
 * Reports whether [file] is a fragmented MP4, that is, a file whose samples live in `moof`/`mdat`
 * fragments instead of in the `moov` sample tables.
 *
 * Fragmented files written by camera apps and screen recorders routinely carry neither a `sidx` nor
 * an `mfra` index. Without one, ExoPlayer publishes an unseekable timeline (see
 * `FragmentedMp4Extractor`, which falls back to `SeekMap.Unseekable`) and `ProgressiveMediaPeriod`
 * silently rewrites every seek to position 0, while `MediaExtractor.seekTo` and
 * `MediaMetadataRetriever.getFrameAtTime` keep returning the opening frame.
 *
 * Only box headers are read, so the cost is a handful of seeks regardless of the file size.
 */
internal fun isFragmentedMp4(file: File): Boolean {
  if (!file.isFile) {
    return false
  }
  return try {
    RandomAccessFile(file, "r").use { input ->
      val fileLength = input.length()
      var position = 0L
      while (position + BOX_HEADER_SIZE <= fileLength) {
        input.seek(position)
        // Box sizes are unsigned, so widen before use to keep sizes above 2 GB positive.
        val declaredSize = input.readInt().toLong() and 0xFFFFFFFFL
        val boxType = input.readInt()
        if (boxType == BOX_TYPE_MOOF) {
          return true
        }
        if (declaredSize == 0L) {
          // A size of 0 means the box runs to the end of the file, so nothing follows it.
          return false
        }
        val headerSize = if (declaredSize == 1L) LARGE_BOX_HEADER_SIZE else BOX_HEADER_SIZE
        if (declaredSize == 1L && position + LARGE_BOX_HEADER_SIZE > fileLength) {
          return false
        }
        // A size of 1 means the real, 64-bit size follows the type.
        val boxSize = if (declaredSize == 1L) input.readLong() else declaredSize
        if (boxSize < headerSize || boxSize > fileLength - position) {
          // Malformed box: the walk cannot continue without looping, overflowing, or reading past
          // EOF.
          Log.w(TAG, "Stopping box scan of ${file.name}: box size $boxSize at offset $position")
          return false
        }
        position += boxSize
      }
      false
    }
  } catch (e: IOException) {
    Log.w(TAG, "Failed to scan ${file.name} for MP4 fragments", e)
    false
  }
}

/**
 * Rewrites [videoFile] in place as a progressive MP4 when it is a fragmented MP4, leaving it
 * untouched otherwise.
 *
 * Remuxing copies the encoded samples into a file with a full sample table, so the result is
 * seekable, without re-encoding and therefore without any quality loss.
 *
 * The work is blocking file IO and runs on [ioDispatcher]. The importer passes the ViewModel's
 * injected dispatcher and tests pass their own, so the default is only a convenience.
 *
 * @return true when the file was rewritten, or false when [videoFile] is not a fragmented MP4.
 * @throws IOException when [videoFile] is a fragmented MP4 and rewriting or replacing it fails.
 */
internal suspend fun normalizeFragmentedMp4(
  videoFile: File,
  ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
): Boolean =
  withContext(ioDispatcher) {
    if (!isFragmentedMp4(videoFile)) {
      return@withContext false
    }
    Log.d(TAG, "Rewriting fragmented MP4 ${videoFile.name} so that it can be seeked")
    val remuxedFile =
      File(videoFile.parentFile, "${videoFile.nameWithoutExtension}$NORMALIZED_FILE_SUFFIX")
    try {
      val rewritten =
        remuxToProgressiveMp4(source = videoFile, destination = remuxedFile) &&
          replaceWithRemuxedFile(videoFile = videoFile, remuxedFile = remuxedFile)
      if (!rewritten) {
        throw IOException("Failed to rewrite fragmented MP4 ${videoFile.name}")
      }
      true
    } catch (e: CancellationException) {
      throw e
    } catch (e: IOException) {
      Log.e(TAG, "Failed to rewrite fragmented MP4 ${videoFile.name}", e)
      throw e
    } catch (e: Exception) {
      Log.e(TAG, "Failed to rewrite fragmented MP4 ${videoFile.name}", e)
      throw IOException("Failed to rewrite fragmented MP4 ${videoFile.name}", e)
    } finally {
      // Normally just a scratch file. When the rollback below could not put the original back,
      // though, this may be the only complete video left, so it only goes away while the original
      // is known to be in place.
      if (videoFile.exists() && remuxedFile.exists()) {
        remuxedFile.delete()
      }
    }
  }

/**
 * Puts [remuxedFile] in the place of [videoFile].
 *
 * The original is moved aside rather than deleted, and moved back when the replacement cannot be
 * put in place. Deleting it up front would be simpler, but the remux has just doubled what the
 * video occupies on disk, so a full volume is exactly when the copy that follows is most likely to
 * fail, and that is precisely when the project must not be left pointing at a video that is gone. A
 * video that cannot be seeked still beats no video at all.
 */
@VisibleForTesting
internal fun replaceWithRemuxedFile(videoFile: File, remuxedFile: File): Boolean {
  val backupFile = File(videoFile.parentFile, "${videoFile.name}$BACKUP_FILE_SUFFIX")
  if (videoFile.exists() && !videoFile.renameTo(backupFile)) {
    Log.e(TAG, "Failed to set the fragmented copy of ${videoFile.name} aside")
    return false
  }
  // A rename is all this normally takes, since both files sit in the project directory. The copy
  // is the fallback for the filesystems that refuse one.
  val replaced =
    remuxedFile.renameTo(videoFile) ||
      try {
        remuxedFile.copyTo(videoFile, overwrite = true)
        true
      } catch (e: IOException) {
        Log.e(TAG, "Failed to put the remuxed copy of ${videoFile.name} in place", e)
        false
      }
  if (!replaced) {
    // Clear away whatever a half finished copy left behind before restoring the original.
    videoFile.delete()
    val restored =
      backupFile.renameTo(videoFile) ||
        try {
          backupFile.copyTo(videoFile, overwrite = true)
          true
        } catch (e: IOException) {
          Log.e(TAG, "Failed to restore the original copy of ${videoFile.name}", e)
          false
        }
    if (restored) {
      backupFile.delete()
    }
    return false
  }
  backupFile.delete()
  return true
}

/**
 * Copies every audio and video sample of [source] into [destination] in storage order, producing an
 * MP4 with the sample tables that seeking depends on.
 *
 * The samples are read sequentially, which is the one access pattern a fragmented MP4 without an
 * index supports.
 */
private suspend fun remuxToProgressiveMp4(source: File, destination: File): Boolean {
  var extractor: MediaExtractor? = null
  var muxer: MediaMuxer? = null
  var muxerStarted = false
  try {
    extractor = MediaExtractor().apply { setDataSource(source.absolutePath) }
    muxer = MediaMuxer(destination.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    val rotationDegrees = videoRotationDegrees(source)
    if (rotationDegrees != 0) {
      muxer.setOrientationHint(rotationDegrees)
    }

    // Maps the extractor track indices onto the muxer ones, which are handed out in addTrack order.
    val trackIndexMap = mutableMapOf<Int, Int>()
    var sampleBufferSize = INITIAL_SAMPLE_BUFFER_SIZE
    var videoTrackAdded = false
    for (trackIndex in 0 until extractor.trackCount) {
      val format = extractor.getTrackFormat(trackIndex)
      val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
      val isVideo = mime.startsWith("video/")
      if (!isVideo && !mime.startsWith("audio/")) {
        continue
      }
      // The MP4 muxer rejects the codecs it cannot carry, AC-3 audio among them. Dropping such a
      // track costs less than leaving the whole video unseekable, which is what returning here
      // would do, so only a video track that cannot be carried is fatal.
      val muxerTrackIndex =
        try {
          muxer.addTrack(format)
        } catch (e: IllegalArgumentException) {
          Log.w(TAG, "Skipping the $mime track of ${source.name}, which cannot be muxed", e)
          continue
        }
      if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
        sampleBufferSize =
          maxOf(sampleBufferSize, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
      }
      trackIndexMap[trackIndex] = muxerTrackIndex
      videoTrackAdded = videoTrackAdded || isVideo
      extractor.selectTrack(trackIndex)
    }
    if (!videoTrackAdded) {
      Log.e(TAG, "No video track that can be muxed was found in ${source.name}")
      return false
    }

    muxer.start()
    muxerStarted = true

    val sampleBuffer = SampleBuffer(sampleBufferSize)
    val bufferInfo = MediaCodec.BufferInfo()
    var sampleCount = 0
    while (true) {
      coroutineContext.ensureActive()
      val trackIndex = extractor.sampleTrackIndex
      if (trackIndex < 0) {
        break
      }
      val sampleTimeUs = extractor.sampleTime
      val muxerTrackIndex = trackIndexMap[trackIndex]
      if (muxerTrackIndex != null && sampleTimeUs >= 0L) {
        bufferInfo.offset = 0
        bufferInfo.size = sampleBuffer.read(extractor)
        if (bufferInfo.size > 0) {
          bufferInfo.presentationTimeUs = sampleTimeUs
          bufferInfo.flags = muxerFlagsFor(extractor.sampleFlags)
          muxer.writeSampleData(muxerTrackIndex, sampleBuffer.buffer, bufferInfo)
          sampleCount++
        }
      }
      if (!extractor.advance()) {
        break
      }
    }

    if (sampleCount == 0) {
      Log.e(TAG, "No samples could be read from ${source.name}")
      return false
    }

    muxer.stop()
    muxerStarted = false
    Log.d(TAG, "Remuxed ${source.name} into $sampleCount progressive samples")
    return true
  } finally {
    if (muxerStarted) {
      try {
        muxer?.stop()
      } catch (_: Exception) {}
    }
    try {
      muxer?.release()
    } catch (_: Exception) {}
    try {
      extractor?.release()
    } catch (_: Exception) {}
  }
}

/**
 * A direct buffer that samples are copied through, grown on demand.
 *
 * Extractors advertise a maximum sample size through [MediaFormat.KEY_MAX_INPUT_SIZE], but the
 * value is missing or too low on some files, and a sample that does not fit makes
 * [MediaExtractor.readSampleData] throw instead of returning a size. Growing past the largest
 * sample once is cheaper than letting the remux fail on a single oversized keyframe.
 */
private class SampleBuffer(initialSize: Int) {
  var buffer: ByteBuffer =
    ByteBuffer.allocateDirect(
      initialSize.coerceIn(INITIAL_SAMPLE_BUFFER_SIZE, MAX_SAMPLE_BUFFER_SIZE)
    )
    private set

  /**
   * Copies the sample [extractor] is positioned on into [buffer].
   *
   * @return the sample size in bytes, or -1 when the extractor has no sample left.
   */
  fun read(extractor: MediaExtractor): Int {
    while (true) {
      try {
        return extractor.readSampleData(buffer, 0)
      } catch (e: IllegalArgumentException) {
        if (buffer.capacity() >= MAX_SAMPLE_BUFFER_SIZE) {
          throw e
        }
        val grownSize = (buffer.capacity() * 2).coerceAtMost(MAX_SAMPLE_BUFFER_SIZE)
        Log.d(TAG, "Growing the sample buffer to $grownSize bytes")
        buffer = ByteBuffer.allocateDirect(grownSize)
      }
    }
  }
}

/** Translates [MediaExtractor] sample flags into the matching [MediaCodec] buffer flags. */
private fun muxerFlagsFor(sampleFlags: Int): Int {
  var flags = 0
  if ((sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
    flags = flags or MediaCodec.BUFFER_FLAG_KEY_FRAME
  }
  if ((sampleFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME) != 0) {
    flags = flags or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
  }
  return flags
}

/**
 * Returns the rotation [file] should be displayed with, as one of 0, 90, 180 or 270 degrees.
 *
 * The rotation lives in the track header matrix, which remuxing does not carry over, so it has to
 * be read here and handed to the muxer as an orientation hint.
 */
private fun videoRotationDegrees(file: File): Int {
  val retriever = MediaMetadataRetriever()
  return try {
    retriever.setDataSource(file.absolutePath)
    val rotation =
      retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull()
        ?: 0
    val normalized = ((rotation % 360) + 360) % 360
    if (normalized == 90 || normalized == 180 || normalized == 270) normalized else 0
  } catch (e: Exception) {
    Log.w(TAG, "Failed to read the rotation of ${file.name}", e)
    0
  } finally {
    try {
      retriever.release()
    } catch (_: Exception) {}
  }
}
