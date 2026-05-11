/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.ai.edge.gallery.notifications

import android.app.AlarmManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import com.google.ai.edge.gallery.proto.ScheduledNotification
import com.google.ai.edge.gallery.proto.ScheduledNotifications
import com.google.protobuf.InvalidProtocolBufferException
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// Manages the scheduled notifications in the agent chat skill, including loading and saving
// notifications to the disk, and canceling/updating scheduled notifications when they are removed.
// This is a global singleton object, and is thread-safe.
@Singleton
class NotificationScheduleManager
@Inject
constructor(@ApplicationContext private val context: Context) {
  private val dataStore: DataStore<ScheduledNotifications> =
    DataStoreFactory.create(
      serializer = ScheduledNotificationsSerializer,
      produceFile = { File(context.filesDir, "scheduled_notifications.pb") },
    )
  private val TAG = "NotificationScheduleManager"

  // Use a coroutine scope for IO operations to avoid blocking the calling thread.
  private val coroutineScope = CoroutineScope(Dispatchers.IO)

  private val _scheduledNotifications = MutableStateFlow<List<ScheduledNotification>>(emptyList())
  val scheduledNotifications = _scheduledNotifications.asStateFlow()

  init {
    loadNotifications()
  }

  fun initialize() = Unit

  /** Loads the scheduled notifications from the disk. */
  private fun loadNotifications() {
    coroutineScope.launch {
      try {
        val file = File(context.filesDir, "scheduled_notifications.pb")
        if (file.exists()) {
          val data = file.inputStream().use { ScheduledNotificationsSerializer.readFrom(it) }
          _scheduledNotifications.value = data.notificationList
        }
      } catch (e: Exception) {
        // Ignore on read fault
      }
    }
  }

  /** Saves the scheduled notifications to the disk. */
  private fun saveNotifications() {
    coroutineScope.launch {
      dataStore.updateData {
        ScheduledNotifications.newBuilder()
          .addAllNotification(_scheduledNotifications.value)
          .build()
      }
    }
  }

  /**
   * Schedules a notification and returns true if the notification was scheduled successfully,
   * otherwise returns false.
   */
  fun scheduleNotification(notification: ScheduledNotification): Boolean {
    if (!setAlarmForNotification(notification)) {
      return false
    }
    _scheduledNotifications.update { it + notification }
    saveNotifications()
    return true
  }

  /**
   * Sets an alarm for a notification. If the notification has a date, the alarm is set for the
   * specified date and time. Otherwise, the alarm is set for the specified time on the current day.
   * If the specified time is in the past, the alarm is set for the specified time on the next day.
   * If repeatDaily is true, the alarm is set to repeat daily.
   *
   * @param notification The notification to schedule.
   * @return True if the notification was scheduled successfully, otherwise false.
   */
  private fun setAlarmForNotification(notification: ScheduledNotification): Boolean {
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    // On Android S (API 31) and later, we must check whether we have permission to set exact
    // alarms. If we don't have permission, don't schedule alarms for notifications.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      if (!alarmManager.canScheduleExactAlarms()) {
        // TODO: Request permission from user.
        Log.e(TAG, "Failed to schedule notification: permission not granted")
        return false
      }
    }

    val pendingIntent =
      NotificationPendingIntentHelper.buildNotificationPendingIntent(
        context,
        notification.id,
        notification.title,
        notification.message,
        notification.deeplink,
        notification.repeatDaily,
        notification.hour,
        notification.minute,
        notification.channelId,
        notification.channelName,
      )

    val calendar =
      Calendar.getInstance().apply {
        timeInMillis = System.currentTimeMillis()
        if (notification.hasYear() && notification.hasMonth() && notification.hasDay()) {
          set(Calendar.YEAR, notification.year)
          set(Calendar.MONTH, notification.month - 1)
          set(Calendar.DAY_OF_MONTH, notification.day)
        }
        set(Calendar.HOUR_OF_DAY, notification.hour)
        set(Calendar.MINUTE, notification.minute)
        set(Calendar.SECOND, 0)
        if (before(Calendar.getInstance())) {
          if (
            notification.repeatDaily ||
              (!notification.hasYear() && !notification.hasMonth() && !notification.hasDay())
          ) {
            add(Calendar.DATE, 1)
          }
        }
      }

    if (notification.repeatDaily) {
      alarmManager.setRepeating(
        AlarmManager.RTC_WAKEUP,
        calendar.timeInMillis,
        AlarmManager.INTERVAL_DAY,
        pendingIntent,
      )
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      alarmManager.setExactAndAllowWhileIdle(
        AlarmManager.RTC_WAKEUP,
        calendar.timeInMillis,
        pendingIntent,
      )
    } else {
      alarmManager.setExact(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent)
    }
    return true
  }

  /** Reschedules all loaded notifications. Typically called after device boot. */
  fun rescheduleAllNotifications() {
    coroutineScope.launch {
      try {
        val file = File(context.filesDir, "scheduled_notifications.pb")
        if (file.exists()) {
          val data = file.inputStream().use { ScheduledNotificationsSerializer.readFrom(it) }
          for (notification in data.notificationList) {
            val unused = setAlarmForNotification(notification)
          }
        }
      } catch (e: Exception) {
        Log.e(TAG, "Failed to reschedule notifications", e)
      }
    }
  }

  /** Removes a notification from the schedule and cancels the alarm for the notification. */
  fun removeNotification(id: String) {
    val removed = _scheduledNotifications.value.find { it.id == id }
    removed?.let {
      val pendingIntent =
        NotificationPendingIntentHelper.buildNotificationPendingIntent(
          context,
          it.id,
          it.title,
          it.message,
          it.deeplink,
          it.repeatDaily,
          it.hour,
          it.minute,
          it.channelId,
          it.channelName,
        )
      val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
      alarmManager.cancel(pendingIntent)
    }
    _scheduledNotifications.update { list -> list.filter { it.id != id } }
    saveNotifications()
  }
}

object ScheduledNotificationsSerializer : Serializer<ScheduledNotifications> {
  override val defaultValue: ScheduledNotifications = ScheduledNotifications.getDefaultInstance()

  override suspend fun readFrom(input: InputStream): ScheduledNotifications {
    try {
      return ScheduledNotifications.parseFrom(input)
    } catch (exception: InvalidProtocolBufferException) {
      throw CorruptionException("Cannot read proto.", exception)
    }
  }

  override suspend fun writeTo(t: ScheduledNotifications, output: OutputStream) = t.writeTo(output)
}

@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface NotificationScheduleManagerEntryPoint {
  fun notificationScheduleManager(): NotificationScheduleManager
}
