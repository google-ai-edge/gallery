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

package com.google.ai.edge.gallery.services.speechservices

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.pm.PackageInfoCompat

/**
 * Version requirements for Speech Services by Google, which serves both ML Kit GenAI Speech
 * Recognition and Text-to-Speech.
 */
object SpeechServicesVersion {
  /** Package name of Speech Services by Google. */
  const val PACKAGE_NAME = "com.google.android.tts"

  /** Minimum Speech Services version code that serves the ML Kit GenAI speech features. */
  const val MIN_VERSION_CODE = 210665810L

  /** Play Store page where the user can install or update Speech Services. */
  const val PLAY_STORE_URL = "https://play.google.com/store/apps/details?id=$PACKAGE_NAME"

  /** Returns the installed Speech Services version code, or `-1` if it is not installed. */
  fun versionCode(context: Context): Long =
    try {
      PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(PACKAGE_NAME, 0))
    } catch (e: PackageManager.NameNotFoundException) {
      -1L
    }

  /** Returns whether [versionCode] meets [MIN_VERSION_CODE]. */
  fun isUpToDate(versionCode: Long): Boolean = versionCode >= MIN_VERSION_CODE

  /** Returns whether the installed Speech Services meets [MIN_VERSION_CODE]. */
  fun isUpToDate(context: Context): Boolean = isUpToDate(versionCode(context))
}
