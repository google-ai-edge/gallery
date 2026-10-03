package android.app

class NotificationChannel(val id: String, val name: CharSequence, val importance: Int)

class NotificationManager {
  fun createNotificationChannel(channel: NotificationChannel) {}
  fun notify(id: Int, notification: Any?) {}
  fun cancel(id: Int) {}

  companion object {
    const val IMPORTANCE_DEFAULT = 3
    const val IMPORTANCE_HIGH = 4
    const val IMPORTANCE_LOW = 2
  }
}
