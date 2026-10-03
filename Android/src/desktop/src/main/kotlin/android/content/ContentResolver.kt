package android.content

import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

open class ContentResolver {
  private fun toFile(uri: Uri): File? {
    val str = uri.toString()
    if (str.startsWith("file:")) {
      val parsed = runCatching { File(java.net.URI(str)) }.getOrNull()
      if (parsed != null) return parsed
      var p = str.removePrefix("file:")
      while (p.startsWith("/")) p = p.substring(1)
      return File(p)
    }
    return File(str)
  }

  fun query(
    uri: Uri,
    projection: Array<String>?,
    selection: String?,
    selectionArgs: Array<String>?,
    sortOrder: String?,
  ): Cursor? {
    val file = toFile(uri) ?: return null
    if (!file.exists()) return null
    return object : Cursor {
      override fun moveToFirst(): Boolean = true
      override fun getColumnIndex(columnName: String): Int = getColumnIndexOrThrow(columnName)
      override fun getColumnIndexOrThrow(columnName: String): Int = when (columnName) {
        OpenableColumns.SIZE -> 0
        OpenableColumns.DISPLAY_NAME -> 1
        else -> -1
      }
      override fun getLong(columnIndex: Int): Long = if (columnIndex == 0) file.length() else 0L
      override fun getString(columnIndex: Int): String = if (columnIndex == 1) file.name else ""
      override fun close() {}
    }
  }

  fun openInputStream(uri: Uri): InputStream? {
    val file = toFile(uri)
    if (file != null && file.exists()) {
      return file.inputStream()
    }
    return runCatching { java.net.URI(uri.toString()).toURL().openStream() }.getOrNull()
  }

  fun openOutputStream(uri: Uri): FileOutputStream? {
    val file = toFile(uri) ?: return null
    return runCatching { FileOutputStream(file) }.getOrNull()
  }

  fun insert(uri: Uri, values: ContentValues): Uri? {
    val name = (values.get(android.provider.MediaStore.Images.Media.DISPLAY_NAME) as? String)
      ?: "image_${System.currentTimeMillis()}.png"
    val picturesDir = File(System.getProperty("user.home"), "Pictures")
    picturesDir.mkdirs()
    val file = File(picturesDir, name)
    return Uri.fromFile(file)
  }

  fun delete(uri: Uri, where: String?, selectionArgs: Array<String>?): Int {
    val path = if (uri.toString().startsWith("file://")) uri.toString().removePrefix("file://") else uri.toString()
    File(path).delete()
    return 1
  }
}
