package android.provider

import android.net.Uri

object MediaStore {
  const val VOLUME_EXTERNAL_PRIMARY = "external_primary"

  object Images {
    object Media {
      val EXTERNAL_CONTENT_URI: Uri = Uri.parse("content://media/external/images/media")
      const val DISPLAY_NAME = "_display_name"
      const val MIME_TYPE = "mime_type"
      const val DATE_ADDED = "date_added"
      const val DATE_TAKEN = "datetaken"
      const val VOLUME_EXTERNAL_PRIMARY = "external_primary"

      fun getContentUri(volumeName: String): Uri = EXTERNAL_CONTENT_URI
    }
  }
}
