/*
 * Copyright 2026 Pascal Fritzsche
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

package com.google.ai.edge.gallery.morgenschiss

import android.content.ContentResolver
import android.net.Uri
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * File fingerprint shared with morgenschiss (client/Apps/Shared/fileFingerprint.js): SHA-256 over
 * "<size>\n" plus start, middle and end (4 MiB each; files up to 12 MiB whole). The same file gets
 * the same id on every device and after a reinstall.
 */
object Fingerprint {
  const val PART_BYTES = 4L * 1024 * 1024

  /** Byte ranges [start, end) in read order. */
  fun ranges(size: Long): List<LongRange> {
    if (size <= PART_BYTES * 3) return listOf(0L until size)
    val mid = size / 2 - PART_BYTES / 2
    return listOf(0L until PART_BYTES, mid until mid + PART_BYTES, size - PART_BYTES until size)
  }

  /** [read] returns the bytes of one range. */
  fun compute(size: Long, read: (LongRange) -> ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update("$size\n".toByteArray())
    for (range in ranges(size)) digest.update(read(range))
    return digest.digest().joinToString("") { "%02x".format(it) }
  }

  /** Reads only the three ranges from a MediaStore file. */
  fun ofUri(resolver: ContentResolver, uri: Uri, size: Long): String? =
    resolver.openFileDescriptor(uri, "r")?.use { pfd ->
      FileInputStream(pfd.fileDescriptor).channel.use { channel ->
        compute(size) { range ->
          val len = (range.last - range.first + 1).toInt()
          val buf = ByteBuffer.allocate(maxOf(len, 0))
          var pos = range.first
          while (buf.hasRemaining()) {
            val n = channel.read(buf, pos)
            if (n < 0) break
            pos += n
          }
          buf.array().copyOf(buf.position())
        }
      }
    }
}
