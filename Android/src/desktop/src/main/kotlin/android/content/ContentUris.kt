package android.content

import android.net.Uri

object ContentUris {
  @JvmStatic
  fun withAppendedId(builder: Uri.Builder, id: Long): Uri.Builder = builder.appendPath(id.toString())

  @JvmStatic
  fun appendId(builder: Uri.Builder, id: Long): Uri.Builder = builder.appendPath(id.toString())
}
