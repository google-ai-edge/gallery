/*
 * Copyright 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.ai.edge.gallery.eval

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class EvalReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val action = intent.action
    Log.i(TAG, "Received action: $action")
    val serviceIntent =
      Intent(context, EvalService::class.java).apply {
        this.action = action
        if (intent.hasExtra("port")) {
          putExtra("port", intent.getIntExtra("port", 8080))
        }
      }

    if (action == EvalService.ACTION_STOP_SERVER) {
      context.stopService(Intent(context, EvalService::class.java))
    } else {
      try {
        context.startForegroundService(serviceIntent)
      } catch (e: Exception) {
        Log.e(TAG, "Failed to start service", e)
      }
    }
  }

  companion object {
    private const val TAG = "EvalReceiver"
  }
}
