package androidx.exifinterface.media

import java.io.InputStream
import java.io.File

class ExifInterface(stream: InputStream) {
  constructor(filename: String) : this(File(filename).inputStream())

  companion object {
    const val TAG_ORIENTATION = "Orientation"
    const val ORIENTATION_NORMAL = 1
    const val ORIENTATION_ROTATE_90 = 6
    const val ORIENTATION_ROTATE_180 = 3
    const val ORIENTATION_ROTATE_270 = 8
    const val ORIENTATION_UNDEFINED = 0
    const val ORIENTATION_FLIP_HORIZONTAL = 2
    const val ORIENTATION_FLIP_VERTICAL = 4
    const val ORIENTATION_TRANSPOSE = 5
    const val ORIENTATION_TRANSVERSE = 7
  }

  fun getAttributeInt(tag: String, defaultValue: Int): Int = defaultValue
}
