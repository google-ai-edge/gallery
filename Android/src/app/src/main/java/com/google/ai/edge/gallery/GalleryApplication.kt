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

import android.app.Application
import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import androidx.datastore.dataStore
import com.google.ai.edge.gallery.common.writeLaunchInfo
import com.google.ai.edge.gallery.data.AppContainer
import com.google.ai.edge.gallery.data.DefaultAppContainer
import com.google.ai.edge.gallery.proto.Settings
import com.google.ai.edge.gallery.ui.theme.ThemeSettings
import com.google.protobuf.InvalidProtocolBufferException
import java.io.InputStream
import java.io.OutputStream

object SettingsSerializer : Serializer<Settings> {
  override val defaultValue: Settings = Settings.getDefaultInstance()

  override suspend fun readFrom(input: InputStream): Settings {
    try {
      return Settings.parseFrom(input)
    } catch (exception: InvalidProtocolBufferException) {
      throw CorruptionException("Cannot read proto.", exception)
    }
  }

  override suspend fun writeTo(t: Settings, output: OutputStream) = t.writeTo(output)
}

private val Context.dataStore: DataStore<Settings> by
  dataStore(fileName = "settings.pb", serializer = SettingsSerializer)

class GalleryApplication : Application() {
  /** AppContainer instance used by the rest of classes to obtain dependencies */
  lateinit var container: AppContainer

  override fun onCreate() {
    super.onCreate()

    writeLaunchInfo(context = this)
    container = DefaultAppContainer(this, dataStore)

    // Load saved theme.
    ThemeSettings.themeOverride.value = container.dataStoreRepository.readTheme()
  }
}
