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

/** What the sync needs to know about a local file. */
data class LocalFile(val mediaId: Long, val size: Long, val dateModifiedSec: Long, val folder: String, val isVideo: Boolean)

data class SyncPlan(
  /** Files whose fingerprint is missing or stale. */
  val needFingerprint: List<LocalFile>,
  /** Rows of files that no longer exist. */
  val goneRows: List<MediaIdRow>,
  /** Server ids no local file has any more. */
  val removeFromServer: List<String>,
  /** Already on the server (e.g. after a reinstall): only record that locally. */
  val alreadyIndexed: List<LocalFile>,
  /** Missing on the server or moved to another folder. */
  val upload: List<LocalFile>,
)

object SyncPlanner {
  const val BATCH_SIZE = 16

  fun plan(files: List<LocalFile>, rows: Map<Long, MediaIdRow>, serverIds: Set<String>?): SyncPlan {
    val needFp = files.filter { f -> rows[f.mediaId]?.let { it.size != f.size || it.dateModifiedSec != f.dateModifiedSec } ?: true }
    val needIds = needFp.mapTo(HashSet()) { it.mediaId }
    val present = files.associateBy { it.mediaId }
    val gone = rows.values.filter { it.mediaId !in present }
    val fresh = files.filter { f -> f.mediaId !in needIds }
    // a file manager move gives the file a new MediaStore id; the gone row knows the old folder
    val goneFolder = gone.filter { it.serverFolder != null }.associate { it.fingerprint to it.serverFolder }
    val liveFps = fresh.mapNotNull { rows[it.mediaId]?.fingerprint }.toSet()
    // a server id is only removed when no current file has that fingerprint (copies share one id)
    val remove =
      if (serverIds == null) emptyList()
      else gone.map { it.fingerprint }.filter { it in serverIds && it !in liveFps }.distinct()
    val already = ArrayList<LocalFile>()
    val upload = ArrayList<LocalFile>()
    if (serverIds != null) {
      // copies share a fingerprint and a server entry: send each fingerprint once
      val queued = HashSet<String>()
      for (f in fresh) {
        val row = rows.getValue(f.mediaId)
        when {
          row.serverFailed -> {}
          row.fingerprint !in serverIds -> if (queued.add(row.fingerprint)) upload += f
          row.serverFolder == null ->
            if (goneFolder[row.fingerprint]?.let { it != f.folder } == true) {
              if (queued.add(row.fingerprint)) upload += f
            } else already += f
          row.serverFolder != f.folder -> if (queued.add(row.fingerprint)) upload += f
        }
      }
    }
    return SyncPlan(needFp, gone, remove, already, upload)
  }

  /** Indexed videos whose scenes are missing or from an older sampling; one per fingerprint. */
  fun needScenes(files: List<LocalFile>, rows: Map<Long, MediaIdRow>): List<LocalFile> {
    val seen = HashSet<String>()
    return files.filter { f ->
      val row = rows[f.mediaId] ?: return@filter false
      f.isVideo && row.serverFolder != null && !row.serverFailed && row.scenes < MediaIdStore.SCENE_VERSION && seen.add(row.fingerprint)
    }
  }

  /** Indexed videos whose speech the server does not have yet (per [serverSpeech] or the local mark). */
  fun needSpeech(files: List<LocalFile>, rows: Map<Long, MediaIdRow>, serverSpeech: Set<String>): List<LocalFile> {
    val seen = HashSet<String>()
    return files.filter { f ->
      val row = rows[f.mediaId] ?: return@filter false
      f.isVideo && row.serverFolder != null && !row.serverFailed && row.fingerprint !in serverSpeech &&
        row.speech < MediaIdStore.SPEECH_VERSION && seen.add(row.fingerprint)
    }
  }

  /** Photos and videos in separate batches: the server rejects nothing, but photo batches are slow. */
  fun batches(upload: List<LocalFile>): List<List<LocalFile>> =
    upload.partition { !it.isVideo }.toList().flatMap { it.chunked(BATCH_SIZE) }
}
