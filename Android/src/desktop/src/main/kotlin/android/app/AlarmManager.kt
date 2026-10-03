package android.app

class AlarmManager {
  fun setRepeating(type: Int, triggerAtMillis: Long, intervalMillis: Long, operation: PendingIntent) {}
  fun setAndAllowWhileIdle(type: Int, triggerAtMillis: Long, operation: PendingIntent) {}
  fun setExactAndAllowWhileIdle(type: Int, triggerAtMillis: Long, operation: PendingIntent) {}
  fun cancel(operation: PendingIntent) {}

  companion object {
    const val RTC_WAKEUP = 0
    const val RTC = 1
    const val ELAPSED_REALTIME_WAKEUP = 2
    const val ELAPSED_REALTIME = 3
    const val INTERVAL_DAY = 86400000L
    const val INTERVAL_FIFTEEN_MINUTES = 900000L
    const val INTERVAL_HALF_DAY = 43200000L
    const val INTERVAL_HALF_HOUR = 1800000L
    const val INTERVAL_HOUR = 3600000L
  }
}
