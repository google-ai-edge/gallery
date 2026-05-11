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
package com.google.ai.edge.gallery.customtasks.agentchat

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.net.toUri
import com.google.ai.edge.gallery.notifications.NotificationScheduleManagerEntryPoint
import com.google.ai.edge.gallery.proto.ScheduledNotification
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import dagger.hilt.android.EntryPointAccessors
import java.lang.Exception
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@JsonClass(generateAdapter = true)
data class SendEmailParams(
  val extra_email: String,
  val extra_subject: String,
  val extra_text: String,
)

@JsonClass(generateAdapter = true)
data class SendSmsParams(val phone_number: String, val sms_body: String)

@JsonClass(generateAdapter = true)
data class CreateCalendarEventParams(
  val title: String,
  val description: String,
  val begin_time: String,
  val end_time: String,
)

enum class IntentAction(val action: String) {
  SEND_EMAIL("send_email"),
  SEND_SMS("send_sms"),
  CREATE_CALENDAR_EVENT("create_calendar_event"),
  GET_CURRENT_DATE_AND_TIME("get_current_date_and_time"),
  SCHEDULE_NOTIFICATION("schedule_notification");

  companion object {
    fun from(action: String): IntentAction? = entries.find { it.action == action }
  }
}

@JsonClass(generateAdapter = true)
data class ScheduleNotificationParams(
  val title: String,
  val message: String,
  val hour: Int,
  val minute: Int,
  val year: Int? = null,
  val month: Int? = null,
  val day: Int? = null,
  val repeat_daily: Boolean? = null,
  val deeplink: String? = null,
)

object IntentHandler {
  private const val TAG = "IntentHandler"

  fun handleAction(context: Context, action: String, parameters: String): String {
    return when (IntentAction.from(action)) {
      IntentAction.SEND_EMAIL -> {
        try {
          val moshi = Moshi.Builder().build()
          val jsonAdapter = moshi.adapter(SendEmailParams::class.java)
          val params = jsonAdapter.fromJson(parameters)
          if (params != null) {
            val intent =
              Intent(Intent.ACTION_SEND).apply {
                data = "mailto:".toUri()
                type = "text/plain"
                putExtra(Intent.EXTRA_EMAIL, arrayOf(params.extra_email))
                putExtra(Intent.EXTRA_SUBJECT, params.extra_subject)
                putExtra(Intent.EXTRA_TEXT, params.extra_text)
              }
            context.startActivity(intent)
            "succeeded"
          } else {
            Log.e(TAG, "Failed to parse send_email parameters: $parameters")
            "failed"
          }
        } catch (e: Exception) {
          Log.e(TAG, "Failed to parse send_email parameters: $parameters", e)
          "failed"
        }
      }
      IntentAction.SEND_SMS -> {
        try {
          val moshi = Moshi.Builder().build()
          val jsonAdapter = moshi.adapter(SendSmsParams::class.java)
          val params = jsonAdapter.fromJson(parameters)
          if (params != null) {
            val uri = "smsto:${params.phone_number}".toUri()
            val intent = Intent(Intent.ACTION_SENDTO, uri)
            intent.putExtra("sms_body", params.sms_body)
            context.startActivity(intent)
            "succeeded"
          } else {
            Log.e(TAG, "Failed to parse send_sms parameters: $parameters")
            "failed"
          }
        } catch (e: Exception) {
          Log.e(TAG, "Failed to parse send_sms parameters: $parameters", e)
          "failed"
        }
      }
      IntentAction.CREATE_CALENDAR_EVENT -> {
        try {
          val moshi = Moshi.Builder().build()
          val jsonAdapter = moshi.adapter(CreateCalendarEventParams::class.java)
          val params = jsonAdapter.fromJson(parameters)
          if (params != null) {
            val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
            val beginTimeMillis = format.parse(params.begin_time)?.time ?: 0L
            val endTimeMillis = format.parse(params.end_time)?.time ?: 0L
            val intent =
              Intent(Intent.ACTION_INSERT).apply {
                data = android.provider.CalendarContract.Events.CONTENT_URI
                putExtra(android.provider.CalendarContract.Events.TITLE, params.title)
                putExtra(android.provider.CalendarContract.Events.DESCRIPTION, params.description)
                putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, beginTimeMillis)
                putExtra(android.provider.CalendarContract.EXTRA_EVENT_END_TIME, endTimeMillis)
              }
            context.startActivity(intent)
            "succeeded"
          } else {
            Log.e(TAG, "Failed to parse create_calendar_event parameters: $parameters")
            "failed"
          }
        } catch (e: Exception) {
          Log.e(TAG, "Failed to parse create_calendar_event parameters: $parameters", e)
          "failed"
        }
      }
      IntentAction.GET_CURRENT_DATE_AND_TIME -> {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss EEEE", Locale.getDefault())
        val currentDateAndTime = sdf.format(Date())
        Log.d(
          TAG,
          "get_current_date_and_time via handleAction. Current date and time: $currentDateAndTime",
        )
        currentDateAndTime
      }
      IntentAction.SCHEDULE_NOTIFICATION -> {
        scheduleNotification(context, parameters)
      }
      null -> "failed"
    }
  }

  fun scheduleNotification(context: Context, parameters: String): String {
    try {
      val moshi = Moshi.Builder().build()
      val jsonAdapter = moshi.adapter(ScheduleNotificationParams::class.java)
      val params = jsonAdapter.fromJson(parameters)
      if (params != null) {
        val notificationProtoBuilder =
          ScheduledNotification.newBuilder()
            .setId(java.util.UUID.randomUUID().toString())
            .setTitle(params.title)
            .setMessage(params.message)
            .setHour(params.hour)
            .setMinute(params.minute)
            .setChannelId("agent_skill_tasks_channel")
            .setChannelName("Agent Skill Task")
        if (params.year != null) {
          notificationProtoBuilder.setYear(params.year)
        }
        if (params.month != null) {
          notificationProtoBuilder.setMonth(params.month)
        }
        if (params.day != null) {
          notificationProtoBuilder.setDay(params.day)
        }
        if (params.deeplink != null) {
          notificationProtoBuilder.setDeeplink(params.deeplink)
        }
        if (params.repeat_daily != null) {
          notificationProtoBuilder.setRepeatDaily(params.repeat_daily)
        }

        val entryPoint =
          EntryPointAccessors.fromApplication(
            context.applicationContext,
            NotificationScheduleManagerEntryPoint::class.java,
          )
        val success =
          entryPoint
            .notificationScheduleManager()
            .scheduleNotification(notificationProtoBuilder.build())
        if (!success) {
          return "failed"
        }
        return "succeeded"
      } else {
        Log.e(TAG, "Failed to parse schedule_notification parameters: $parameters")
        return "failed"
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to parse schedule_notification parameters: $parameters", e)
      return "failed"
    }
  }
}
