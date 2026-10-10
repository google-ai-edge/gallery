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

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Cached fingerprint of one MediaStore item and what the server knows about it. */
data class MediaIdRow(
  val mediaId: Long,
  val size: Long,
  val dateModifiedSec: Long,
  val fingerprint: String,
  /** Folder last sent to the server; null = not indexed there. */
  val serverFolder: String?,
  /** The server could not embed it (e.g. broken frames); do not resend. */
  val serverFailed: Boolean,
)

/**
 * MediaStore id <-> fingerprint. A fingerprint is computed once and only again when size or
 * modification time change.
 */
@Singleton
class MediaIdStore @Inject constructor(@ApplicationContext context: Context) :
  SQLiteOpenHelper(context, "media_ids.db", null, 1) {

  override fun onCreate(db: SQLiteDatabase) {
    db.execSQL(
      "CREATE TABLE ids (media_id INTEGER PRIMARY KEY, size INTEGER NOT NULL, " +
        "date_modified INTEGER NOT NULL, fp TEXT NOT NULL, server_folder TEXT, server_failed INTEGER NOT NULL DEFAULT 0)"
    )
    db.execSQL("CREATE INDEX ids_fp ON ids(fp)")
  }

  override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

  fun all(): Map<Long, MediaIdRow> {
    val out = HashMap<Long, MediaIdRow>()
    readableDatabase
      .rawQuery("SELECT media_id, size, date_modified, fp, server_folder, server_failed FROM ids", null)
      .use { c ->
        while (c.moveToNext()) {
          out[c.getLong(0)] =
            MediaIdRow(c.getLong(0), c.getLong(1), c.getLong(2), c.getString(3), if (c.isNull(4)) null else c.getString(4), c.getInt(5) != 0)
        }
      }
    return out
  }

  /** New or changed fingerprint: the server state starts over. */
  fun putFingerprint(mediaId: Long, size: Long, dateModifiedSec: Long, fingerprint: String) {
    writableDatabase.insertWithOnConflict(
      "ids",
      null,
      ContentValues().apply {
        put("media_id", mediaId)
        put("size", size)
        put("date_modified", dateModifiedSec)
        put("fp", fingerprint)
        putNull("server_folder")
        put("server_failed", 0)
      },
      SQLiteDatabase.CONFLICT_REPLACE,
    )
  }

  fun markIndexed(mediaIds: Collection<Long>, folderOf: (Long) -> String) {
    val db = writableDatabase
    db.beginTransaction()
    try {
      for (id in mediaIds) {
        db.update("ids", ContentValues().apply { put("server_folder", folderOf(id)); put("server_failed", 0) }, "media_id = ?", arrayOf(id.toString()))
      }
      db.setTransactionSuccessful()
    } finally {
      db.endTransaction()
    }
  }

  fun markFailed(mediaIds: Collection<Long>) {
    val db = writableDatabase
    db.beginTransaction()
    try {
      for (id in mediaIds) db.update("ids", ContentValues().apply { put("server_failed", 1) }, "media_id = ?", arrayOf(id.toString()))
      db.setTransactionSuccessful()
    } finally {
      db.endTransaction()
    }
  }

  /** Forget the server state, e.g. after the server lost an id. */
  fun clearServerState(mediaIds: Collection<Long>) {
    val db = writableDatabase
    db.beginTransaction()
    try {
      for (id in mediaIds) db.update("ids", ContentValues().apply { putNull("server_folder"); put("server_failed", 0) }, "media_id = ?", arrayOf(id.toString()))
      db.setTransactionSuccessful()
    } finally {
      db.endTransaction()
    }
  }

  fun delete(mediaIds: Collection<Long>) {
    val db = writableDatabase
    db.beginTransaction()
    try {
      for (id in mediaIds) db.delete("ids", "media_id = ?", arrayOf(id.toString()))
      db.setTransactionSuccessful()
    } finally {
      db.endTransaction()
    }
  }
}
