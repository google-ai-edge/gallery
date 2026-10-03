package androidx.core.app

import android.app.PendingIntent
import android.content.Context

object NotificationCompat {
  const val PRIORITY_DEFAULT = 0
  const val PRIORITY_HIGH = 1

  class Builder(val context: Context, val channelId: String = "") {
    fun setSmallIcon(icon: Int): Builder = this
    fun setContentTitle(title: CharSequence?): Builder = this
    fun setContentText(text: CharSequence?): Builder = this
    fun setPriority(pri: Int): Builder = this
    fun setContentIntent(intent: PendingIntent?): Builder = this
    fun setAutoCancel(autoCancel: Boolean): Builder = this
    fun build(): Any = Any()
  }
}
