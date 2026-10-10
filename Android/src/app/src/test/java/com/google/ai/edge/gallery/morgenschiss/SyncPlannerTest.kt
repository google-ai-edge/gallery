package com.google.ai.edge.gallery.morgenschiss

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncPlannerTest {
  private fun file(id: Long, folder: String = "DCIM/Camera", size: Long = 100, mod: Long = 1, video: Boolean = false) =
    LocalFile(id, size, mod, folder, video)

  private fun row(id: Long, fp: String, folder: String? = null, failed: Boolean = false, size: Long = 100, mod: Long = 1) =
    MediaIdRow(id, size, mod, fp, folder, failed)

  @Test fun newAndChangedFilesNeedAFingerprint() {
    val plan = SyncPlanner.plan(listOf(file(1), file(2, size = 200)), mapOf(2L to row(2, "b")), setOf())
    assertEquals(listOf(1L, 2L), plan.needFingerprint.map { it.mediaId })
    assertEquals(emptyList<LocalFile>(), plan.upload)
  }

  @Test fun uploadsMissingAndMovedFilesButNotFailedOnes() {
    val rows = mapOf(
      1L to row(1, "a"),
      2L to row(2, "b", folder = "Old"),
      3L to row(3, "c", folder = "DCIM/Camera"),
      4L to row(4, "d", failed = true),
      5L to row(5, "e"),
    )
    val plan = SyncPlanner.plan((1L..5L).map { file(it) }, rows, setOf("b", "c", "e"))
    assertEquals(listOf(1L, 2L), plan.upload.map { it.mediaId })
    assertEquals(listOf(5L), plan.alreadyIndexed.map { it.mediaId })
  }

  @Test fun removesServerIdsOnlyWhenNoCopyIsLeft() {
    val rows = mapOf(1L to row(1, "a"), 2L to row(2, "a"), 3L to row(3, "x"))
    val plan = SyncPlanner.plan(listOf(file(2)), rows, setOf("a", "x"))
    assertEquals(listOf(1L, 3L), plan.goneRows.map { it.mediaId }.sorted())
    assertEquals(listOf("x"), plan.removeFromServer)
  }

  @Test fun withoutServerIdsNothingIsUploadedOrRemoved() {
    val plan = SyncPlanner.plan(listOf(file(1)), mapOf(1L to row(1, "a"), 9L to row(9, "z")), null)
    assertEquals(0, plan.upload.size + plan.removeFromServer.size)
  }

  @Test fun batchesKeepPhotosAndVideosApart() {
    val files = (1L..20L).map { file(it, video = it > 17) }
    assertEquals(listOf(16, 1, 3), SyncPlanner.batches(files).map { it.size })
  }

  @Test fun fileMovedByAFileManagerIsResent() {
    // new MediaStore id 2 in a new folder, old row 1 had the same fingerprint in "Old"
    val rows = mapOf(1L to row(1, "a", folder = "Old"), 2L to row(2, "a"))
    val plan = SyncPlanner.plan(listOf(file(2, folder = "New")), rows, setOf("a"))
    assertEquals(listOf(2L), plan.upload.map { it.mediaId })
    assertEquals(emptyList<String>(), plan.removeFromServer)
  }

  @Test fun copiesAreUploadedOnce() {
    val rows = mapOf(1L to row(1, "a"), 2L to row(2, "a"))
    val plan = SyncPlanner.plan(listOf(file(1), file(2)), rows, setOf())
    assertEquals(listOf(1L), plan.upload.map { it.mediaId })
  }

  @Test fun indexedVideosWithoutCurrentScenesNeedScenesOncePerFingerprint() {
    val rows = mapOf(
      1L to MediaIdRow(1, 100, 1, "a", "DCIM", false, 0),
      2L to MediaIdRow(2, 100, 1, "a", "DCIM", false, 0),
      3L to MediaIdRow(3, 100, 1, "b", "DCIM", false, MediaIdStore.SCENE_VERSION),
      4L to MediaIdRow(4, 100, 1, "c", null, false, 0),
      5L to MediaIdRow(5, 100, 1, "d", "DCIM", true, 0),
      6L to MediaIdRow(6, 100, 1, "e", "DCIM", false, 0),
    )
    val files = (1L..5L).map { file(it, folder = "DCIM", video = true) } + file(6, folder = "DCIM")
    assertEquals(listOf(1L), SyncPlanner.needScenes(files, rows).map { it.mediaId })
  }
}
