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

package com.google.ai.edge.gallery.customtasks.ArTranslatorTask

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.dataStoreFile
import com.google.ai.edge.gallery.customtasks.common.CustomTask
import com.google.ai.edge.gallery.proto.Dictionary
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal object ArTranslatorTaskModule {
  @Provides
  @IntoSet
  fun provideArTranslatorTask(@ApplicationContext context: Context): CustomTask {
    return ArTranslatorTask(context)
  }

  @Provides
  @Singleton
  fun provideDictionaryDataStore(@ApplicationContext context: Context): DataStore<Dictionary> {
    return DataStoreFactory.create(
      serializer = DictionarySerializer,
      produceFile = { context.dataStoreFile("dictionary.pb") },
    )
  }
}
