/*
 * Copyright 2025 Google LLC
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
package com.google.ai.edge.gallery.customtasks.mobileactions

import android.util.Log
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam

private const val TAG = "AGMATools"

class MobileActionsTools(val onFunctionCalled: (Action) -> Unit) {
  /** Turns on flashlight. */
  @Tool(description = "Turns the flashlight on")
  fun turnOnFlashlight(): Map<String, String> {
    Log.d(TAG, "turn on flashlight")

    // Call the callback with the recognized action.
    onFunctionCalled(FlashlightAction(enable = true))

    // Return a response object to the model confirming the action.
    return mapOf("result" to "success")
  }

  /** Turns off flashlight. */
  @Tool(description = "Turns the flashlight off")
  fun turnOffFlashlight(): Map<String, String> {
    Log.d(TAG, "turn off flashlight")

    // Call the callback with the recognized action.
    onFunctionCalled(FlashlightAction(enable = false))

    // Return a response object to the model confirming the action.
    return mapOf("result" to "success")
  }

  /** Creates contact. */
  @Tool(description = "Creates a contact in the phone's contact list.")
  fun createContact(
    @ToolParam(description = "The first name of the contact.") firstName: String,
    @ToolParam(description = "The last name of the contact.") lastName: String,
    @ToolParam(description = "The phone number of the contact.") phoneNumber: String,
    @ToolParam(description = "The email address of the contact.") email: String,
  ): Map<String, String> {
    Log.d(
      TAG,
      "create contact. First name: '$firstName', last name: '$lastName', phone number: '$phoneNumber', email: '$email'",
    )

    onFunctionCalled(
      CreateContactAction(
        firstName = firstName,
        lastName = lastName,
        phoneNumber = phoneNumber,
        email = email,
      )
    )

    return mapOf(
      "result" to "success",
      "first_name" to firstName,
      "last_name" to lastName,
      "phone_number" to phoneNumber,
      "email" to email,
    )
  }

  /** Sends email. */
  @Tool(description = "Sends an email.")
  fun sendEmail(
    @ToolParam(description = "The email address of the recipient.") to: String,
    @ToolParam(description = "The subject of the email.") subject: String,
    @ToolParam(description = "The body of the email.") body: String,
  ): Map<String, String> {
    Log.d(TAG, "send email. To: '$to', subject: '$subject', body: '$body'")

    onFunctionCalled(SendEmailAction(to = to, subject = subject, body = body))

    return mapOf("result" to "success", "to" to to, "subject" to subject, "body" to body)
  }

  /** Shows location on map. */
  @Tool(description = "Shows a location on the map.")
  fun showLocationOnMap(
    @ToolParam(
      description =
        "The location to search for. May be the name of a place, a business, or an address."
    )
    location: String
  ): Map<String, String> {
    Log.d(TAG, "Show location on map. Location: '$location'")

    onFunctionCalled(ShowLocationOnMap(location = location))

    return mapOf("result" to "success", "location" to location)
  }

  /** Opens wifi settings. */
  @Tool(description = "Opens the WiFi settings.")
  fun openWifiSettings(): Map<String, String> {
    Log.d(TAG, "Open wifi settings")

    onFunctionCalled(OpenWifiSettingsAction())

    return mapOf("result" to "success")
  }

  /** Creates calendar events. */
  @Tool(description = "Creates a new calendar event.")
  fun createCalendarEvent(
    @ToolParam(description = "The date and time of the event in the format YYYY-MM-DDTHH:MM:SS.")
    datetime: String,
    @ToolParam(description = "The title of the event.") title: String,
  ): Map<String, String> {
    Log.d(TAG, "Create calendar event. Datetime: '$datetime', title: '$title'")

    onFunctionCalled(CreateCalendarEventAction(datetime = datetime, title = title))

    return mapOf("result" to "success", "datetime" to datetime, "title" to title)
  }
}
