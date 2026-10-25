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

package com.google.ai.edge.gallery.ui.common

import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

/**
 * Calls [onBackgrounded] each time the app's window leaves the screen while this composable is in
 * the composition: the user goes Home, switches to another app, or turns the screen off.
 *
 * Listens to the hosting activity's `ON_STOP` rather than the screen's own lifecycle, so it doesn't
 * fire on in-app navigation, and skips the `ON_STOP` of a configuration change, where the activity
 * is recreated right away.
 *
 * Use it to stop work the user can hear or that holds the microphone, such as text-to-speech
 * playback and speech recognition, which Android otherwise lets continue in the background.
 */
@Composable
fun OnAppBackgroundedEffect(onBackgrounded: () -> Unit) {
  val activity = LocalActivity.current as? ComponentActivity ?: return
  LifecycleEventEffect(Lifecycle.Event.ON_STOP, lifecycleOwner = activity) {
    if (!activity.isChangingConfigurations) {
      onBackgrounded()
    }
  }
}
