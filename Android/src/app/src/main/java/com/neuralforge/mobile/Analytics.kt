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

package com.neuralforge.mobile

import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.analytics

private var hasLoggedAnalyticsWarning = false

val firebaseAnalytics: FirebaseAnalytics?
  get() =
    runCatching { Firebase.analytics }
      .onFailure { exception ->
        // Firebase.analytics can throw an exception if goolgle-services is not set up, e.g.,
        // missing google-services.json.
        if (!hasLoggedAnalyticsWarning) {
          Log.w("AGAnalyticsFirebase", "Firebase Analytics is not available", exception)
        }
      }
      .getOrNull()
