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

package com.google.ai.edge.gallery

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class GalleryFcmMessagingService : FirebaseMessagingService() {
  override fun onMessageReceived(remoteMessage: RemoteMessage) {
    // TODO(developer): Handle FCM messages here.
    // Not getting messages here? See why this may be: https://goo.gl/39bRNJ
    Log.d(TAG, "From: ${remoteMessage.from}")

    // Check if message contains a data payload.
    if (remoteMessage.data.isNotEmpty()) {
      Log.d(TAG, "Message data payload: ${remoteMessage.data}")

      // Handle message within 10 seconds
      handleNow()
    }

    // Check if message contains a notification payload.
    remoteMessage.notification?.let { notification ->
      Log.d(TAG, "Message Notification Body: ${notification.body}")
      notification.body?.let { body ->
        sendNotification(notification.title, body, notification.imageUrl)
      }
    }

    // Also if you intend on generating your own notificatisons as a result of a received FCM
    // message, here is where that should be initiated. See sendNotification method below.

  }

  private fun handleNow() {
    Log.d(TAG, "Short lived task is done.")
  }

  private fun sendNotification(title: String?, messageBody: String, imageUrl: android.net.Uri?) {
    val intent = Intent(this, MainActivity::class.java)
    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
    val requestCode = 0
    val pendingIntent =
      PendingIntent.getActivity(this, requestCode, intent, PendingIntent.FLAG_IMMUTABLE)

    val channelId = "gallery_high_priority_push_channel"
    val defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
    val notificationBuilder =
      NotificationCompat.Builder(this, channelId)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle(title ?: getString(R.string.gallery_news_notification_title))
        .setContentText(messageBody)
        .setAutoCancel(true)
        .setSound(defaultSoundUri)
        .setContentIntent(pendingIntent)
        .setPriority(NotificationCompat.PRIORITY_HIGH)

    if (imageUrl != null) {
      try {
        val url = java.net.URL(imageUrl.toString())
        val connection = url.openConnection()
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        val bitmap = android.graphics.BitmapFactory.decodeStream(connection.getInputStream())
        if (bitmap != null) {
          notificationBuilder.setLargeIcon(bitmap)
          notificationBuilder.setStyle(
            NotificationCompat.BigPictureStyle()
              .bigPicture(bitmap)
              .bigLargeIcon(null as android.graphics.Bitmap?)
          )
        }
      } catch (e: Exception) {
        Log.w(TAG, "Failed to download image", e)
      }
    }

    val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    // Since android Oreo notification channel is needed.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel =
        NotificationChannel(
          channelId,
          getString(R.string.gallery_news_notification_title),
          NotificationManager.IMPORTANCE_HIGH,
        )
      notificationManager.createNotificationChannel(channel)
    }

    val notificationId = 0
    notificationManager.notify(notificationId, notificationBuilder.build())
  }

  companion object {
    private const val TAG = "AGFcmMessagingService"
  }
}
