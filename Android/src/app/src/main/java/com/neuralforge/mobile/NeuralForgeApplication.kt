/*
 * Copyright 2025 Neural Forge
 * Based on Google AI Edge Gallery
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

import android.app.Application
import android.util.Log
import com.neuralforge.mobile.data.DataStoreRepository
import com.neuralforge.mobile.ui.theme.ThemeSettings
import com.google.firebase.FirebaseApp
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class NeuralForgeApplication : Application() {

  companion object {
    private const val TAG = "NeuralForgeApp"
  }

  @Inject lateinit var dataStoreRepository: DataStoreRepository

  override fun onCreate() {
    super.onCreate()

    Log.d(TAG, "Neural Forge initializing...")

    // Load saved theme.
    ThemeSettings.themeOverride.value = dataStoreRepository.readTheme()

    FirebaseApp.initializeApp(this)

    Log.d(TAG, "Neural Forge initialized successfully")
  }
}
