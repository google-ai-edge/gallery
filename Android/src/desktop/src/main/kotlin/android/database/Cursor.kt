package android.database

import java.io.Closeable

interface Cursor : Closeable {
  fun moveToFirst(): Boolean
  fun moveToNext(): Boolean = false
  fun getColumnIndex(columnName: String): Int
  fun getColumnIndexOrThrow(columnName: String): Int
  fun getLong(columnIndex: Int): Long
  fun getString(columnIndex: Int): String
  override fun close() {}
}
