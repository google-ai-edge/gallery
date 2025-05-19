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

package com.google.ai.edge.gallery

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

interface AppLifecycleProvider {
  val isAppInForeground: Boolean
}

class GalleryLifecycleProvider : AppLifecycleProvider, DefaultLifecycleObserver {
  private var _isAppInForeground = false

  init {
    ProcessLifecycleOwner.get().lifecycle.addObserver(this)
  }

  override val isAppInForeground: Boolean
    get() = _isAppInForeground

  override fun onResume(owner: LifecycleOwner) {
    _isAppInForeground = true
  }

  override fun onPause(owner: LifecycleOwner) {
    _isAppInForeground = false
  }
}
