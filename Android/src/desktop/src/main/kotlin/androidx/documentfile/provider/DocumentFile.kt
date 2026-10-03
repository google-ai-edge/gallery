package androidx.documentfile.provider

import android.content.Context
import android.net.Uri
import java.io.File

open class DocumentFile private constructor(val file: File) {
  val isDirectory: Boolean get() = file.isDirectory
  val isFile: Boolean get() = file.isFile
  val name: String? get() = file.name
  val uri: Uri get() = Uri.fromFile(file)
  fun exists(): Boolean = file.exists()

  fun listFiles(): Array<DocumentFile> {
    val list = file.listFiles() ?: return emptyArray()
    return list.map { DocumentFile(it) }.toTypedArray()
  }

  fun findFile(displayName: String): DocumentFile? {
    val child = File(file, displayName)
    return if (child.exists()) DocumentFile(child) else null
  }

  companion object {
    @JvmStatic
    fun fromTreeUri(context: Context, treeUri: Uri): DocumentFile? {
      val str = treeUri.toString()
      val path = if (str.startsWith("file://")) str.removePrefix("file://") else str
      val f = File(path)
      return if (f.exists()) DocumentFile(f) else null
    }

    @JvmStatic
    fun fromSingleUri(context: Context, singleUri: Uri): DocumentFile? {
      val str = singleUri.toString()
      val path = if (str.startsWith("file://")) str.removePrefix("file://") else str
      val f = File(path)
      return if (f.exists()) DocumentFile(f) else null
    }

    @JvmStatic
    fun fromFile(file: File): DocumentFile = DocumentFile(file)
  }
}
