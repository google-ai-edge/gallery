package android.app

import android.content.Context
import android.content.Intent

class PendingIntent {
  companion object {
    const val FLAG_UPDATE_CURRENT = 134217728
    const val FLAG_IMMUTABLE = 67108864
    const val FLAG_NO_CREATE = 536870912
    const val FLAG_ONE_SHOT = 1073741824

    @JvmStatic
    fun getActivity(context: Context?, requestCode: Int, intent: Intent?, flags: Int): PendingIntent = PendingIntent()

    @JvmStatic
    fun getBroadcast(context: Context?, requestCode: Int, intent: Intent?, flags: Int): PendingIntent = PendingIntent()
  }
}
