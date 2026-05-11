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

import android.app.PendingIntent
import android.content.Context
import android.content.Intent

// Helper class for building a PendingIntent for a notification.
object NotificationPendingIntentHelper {
  const val EXTRA_ID = "id"
  const val EXTRA_TITLE = "title"
  const val EXTRA_MESSAGE = "message"
  const val EXTRA_DEEPLINK = "deeplink"
  const val EXTRA_REPEAT_DAILY = "repeat_daily"
  const val EXTRA_HOUR = "hour"
  const val EXTRA_MINUTE = "minute"
  const val EXTRA_CHANNEL_ID = "channel_id"
  const val EXTRA_CHANNEL_NAME = "channel_name"

  fun buildNotificationPendingIntent(
    context: Context,
    id: String,
    title: String,
    message: String,
    deeplink: String,
    repeatDaily: Boolean,
    hour: Int,
    minute: Int,
    channelId: String,
    channelName: String,
  ): PendingIntent {
    val receiverClass =
      Class.forName("com.google.ai.edge.gallery.notifications.NotificationReceiver")
    val intent =
      Intent(context, receiverClass).apply {
        putExtra(EXTRA_ID, id)
        putExtra(EXTRA_TITLE, title)
        putExtra(EXTRA_MESSAGE, message)
        putExtra(EXTRA_DEEPLINK, deeplink)
        putExtra(EXTRA_REPEAT_DAILY, repeatDaily)
        putExtra(EXTRA_HOUR, hour)
        putExtra(EXTRA_MINUTE, minute)
        putExtra(EXTRA_CHANNEL_ID, channelId)
        putExtra(EXTRA_CHANNEL_NAME, channelName)
      }
    return PendingIntent.getBroadcast(
      context,
      id.hashCode(),
      intent,
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
  }
}
