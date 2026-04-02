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
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import java.lang.Exception

@JsonClass(generateAdapter = true)
data class SendEmailParams(
  val extra_email: String,
  val extra_subject: String,
  val extra_text: String,
)

@JsonClass(generateAdapter = true)
data class SendSmsParams(val phone_number: String, val sms_body: String)

object IntentHandler {
  private const val TAG = "IntentHandler"

  fun handleAction(context: Context, action: String, parameters: String): Boolean {
    if (action == "send_email") {
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
          return true
        } else {
          Log.e(TAG, "Failed to parse send_email parameters: $parameters")
          return false
        }
      } catch (e: Exception) {
        Log.e(TAG, "Failed to parse send_email parameters: $parameters", e)
        return false
      }
    } else if (action == "send_sms") {
      try {
        val moshi = Moshi.Builder().build()
        val jsonAdapter = moshi.adapter(SendSmsParams::class.java)
        val params = jsonAdapter.fromJson(parameters)
        if (params != null) {
          val uri = "smsto:${params.phone_number}".toUri()
          val intent = Intent(Intent.ACTION_SENDTO, uri)
          intent.putExtra("sms_body", params.sms_body)
          context.startActivity(intent)
          return true
        } else {
          Log.e(TAG, "Failed to parse send_sms parameters: $parameters")
          return false
        }
      } catch (e: Exception) {
        Log.e(TAG, "Failed to parse send_sms parameters: $parameters", e)
        return false
      }
    }
    return false
  }
}
