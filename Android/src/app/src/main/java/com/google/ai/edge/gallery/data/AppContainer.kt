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

package com.google.ai.edge.gallery.data

import android.content.Context
import androidx.datastore.core.DataStore
import com.google.ai.edge.gallery.AppLifecycleProvider
import com.google.ai.edge.gallery.GalleryLifecycleProvider
import com.google.ai.edge.gallery.proto.Settings

/**
 * App container for Dependency injection.
 *
 * This interface defines the dependencies required by the application.
 */
interface AppContainer {
  val context: Context
  val lifecycleProvider: AppLifecycleProvider
  val dataStoreRepository: DataStoreRepository
  val downloadRepository: DownloadRepository
}

/**
 * Default implementation of the AppContainer interface.
 *
 * This class provides concrete implementations for the application's dependencies,
 */
class DefaultAppContainer(ctx: Context, dataStore: DataStore<Settings>) : AppContainer {
  override val context = ctx
  override val lifecycleProvider = GalleryLifecycleProvider()
  override val dataStoreRepository = DefaultDataStoreRepository(dataStore)
  override val downloadRepository = DefaultDownloadRepository(ctx, lifecycleProvider)
}
