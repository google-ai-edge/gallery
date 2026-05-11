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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.android.EntryPointAccessors

/**
 * Reschedules all notifications after the device boots up.
 *
 * This receiver is triggered by the ACTION_BOOT_COMPLETED broadcast.
 */
class BootReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
      Log.d(TAG, "Boot completed received, rescheduling notifications")
      try {
        val entryPoint =
          EntryPointAccessors.fromApplication(
            context.applicationContext,
            NotificationScheduleManagerEntryPoint::class.java,
          )
        entryPoint.notificationScheduleManager().rescheduleAllNotifications()
      } catch (e: Exception) {
        Log.e(TAG, "Failed to reschedule notifications on boot", e)
      }
    }
  }

  companion object {
    private const val TAG = "BootReceiver"
  }
}
