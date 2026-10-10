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
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

const val DIMS = 768

/** One searchable vector: a photo, a video's overview or one of its scenes (then [t] is set). */
data class VectorHit(val fingerprint: String, val t: Double?, val score: Float)

/**
 * The phone's copy of all search vectors (int8), from the Mac (first indexing) or the phone
 * itself. Search runs over it in memory, without the server.
 */
@Singleton
class VectorStore @Inject constructor(@ApplicationContext context: Context) :
  SQLiteOpenHelper(context, "vectors.db", null, 1) {

  override fun onCreate(db: SQLiteDatabase) {
    db.execSQL(
      "CREATE TABLE vec (key TEXT PRIMARY KEY, fp TEXT NOT NULL, t REAL, kind TEXT NOT NULL, " +
        "source TEXT NOT NULL, tokens INTEGER NOT NULL, q BLOB NOT NULL, s REAL NOT NULL)"
    )
    db.execSQL("CREATE INDEX vec_fp ON vec(fp)")
  }

  override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

  /** In memory: one contiguous int8 block plus a factor per row (scale and length folded). */
  private class Index(val fps: Array<String>, val ts: DoubleArray, val q: ByteArray, val k: FloatArray) {
    val size get() = fps.size
  }

  @Volatile private var index: Index? = null

  fun count(): Int = index?.size ?: DatabaseUtils.queryNumEntries(readableDatabase, "vec").toInt()

  data class Row(val key: String, val fingerprint: String, val t: Double?, val kind: String, val source: String, val tokens: Int, val q: ByteArray, val s: Float)

  fun put(rows: List<Row>) {
    if (rows.isEmpty()) return
    val db = writableDatabase
    db.beginTransaction()
    try {
      for (r in rows) {
        db.insertWithOnConflict(
          "vec",
          null,
          ContentValues().apply {
            put("key", r.key); put("fp", r.fingerprint); if (r.t != null) put("t", r.t) else putNull("t")
            put("kind", r.kind); put("source", r.source); put("tokens", r.tokens); put("q", r.q); put("s", r.s)
          },
          SQLiteDatabase.CONFLICT_REPLACE,
        )
      }
      db.setTransactionSuccessful()
    } finally {
      db.endTransaction()
    }
    index = null
  }

  /** Drops the vectors (with scenes) of fingerprints no file on the phone has any more. */
  fun keepOnly(fingerprints: Set<String>) {
    val gone = ArrayList<String>()
    readableDatabase.rawQuery("SELECT DISTINCT fp FROM vec", null).use { c -> while (c.moveToNext()) if (c.getString(0) !in fingerprints) gone += c.getString(0) }
    if (gone.isEmpty()) return
    val db = writableDatabase
    db.beginTransaction()
    try {
      for (fp in gone) db.delete("vec", "fp = ?", arrayOf(fp))
      db.setTransactionSuccessful()
    } finally {
      db.endTransaction()
    }
    index = null
  }

  fun fingerprints(): Set<String> {
    val out = HashSet<String>()
    readableDatabase.rawQuery("SELECT DISTINCT fp FROM vec", null).use { c -> while (c.moveToNext()) out += c.getString(0) }
    return out
  }

  /** The overview vector of a photo or video, for "similar". */
  fun vectorOf(fingerprint: String): FloatArray? =
    readableDatabase.rawQuery("SELECT q, s FROM vec WHERE key = ?", arrayOf(fingerprint)).use { c ->
      if (!c.moveToFirst()) null else unpack(c.getBlob(0), c.getFloat(1))
    }

  @Synchronized
  private fun loadIndex(): Index {
    index?.let { return it }
    val fps = ArrayList<String>()
    val ts = ArrayList<Double>()
    val ks = ArrayList<Float>()
    var q = ByteArray(0)
    readableDatabase.rawQuery("SELECT fp, t, q FROM vec", null).use { c ->
      q = ByteArray(c.count * DIMS)
      var row = 0
      while (c.moveToNext()) {
        val blob = c.getBlob(2)
        if (blob.size != DIMS) continue
        System.arraycopy(blob, 0, q, row * DIMS, DIMS)
        var n = 0L
        for (b in blob) n += b * b
        fps += c.getString(0)
        ts += if (c.isNull(1)) Double.NaN else c.getDouble(1)
        ks += if (n > 0) (1.0 / sqrt(n.toDouble())).toFloat() else 0f
        row++
      }
      if (row * DIMS < q.size) q = q.copyOf(row * DIMS)
    }
    return Index(fps.toTypedArray(), ts.toDoubleArray(), q, ks.toFloatArray()).also { index = it }
  }

  /**
   * Cosine of [query] (unit length) with every vector, best per photo/video; a video counts
   * with its best scene and reports its time. [allow] limits to fingerprints in scope.
   */
  fun search(query: FloatArray, allow: Set<String>? = null, limit: Int = 500): List<VectorHit> {
    val ix = loadIndex()
    val best = HashMap<String, VectorHit>()
    for (row in 0 until ix.size) {
      val fp = ix.fps[row]
      if (allow != null && fp !in allow) continue
      var dot = 0f
      val off = row * DIMS
      for (i in 0 until DIMS) dot += ix.q[off + i] * query[i]
      val score = dot * ix.k[row]
      val cur = best[fp]
      if (cur == null || score > cur.score) best[fp] = VectorHit(fp, ix.ts[row].takeIf { !it.isNaN() }, score)
    }
    return best.values.sortedByDescending { it.score }.take(limit)
  }

  companion object {
    fun unpack(q: ByteArray, s: Float): FloatArray = FloatArray(q.size) { q[it] / 127f * s }

    /** int8 with one scale, like the server stores them. */
    fun pack(v: FloatArray): Pair<ByteArray, Float> {
      var max = 0f
      for (x in v) max = maxOf(max, kotlin.math.abs(x))
      val scale = if (max > 0) max else 1f
      return ByteArray(v.size) { Math.round(v[it] / scale * 127).toByte() } to scale
    }

    fun decode(b64: String): ByteArray = Base64.decode(b64, Base64.DEFAULT)
  }
}
