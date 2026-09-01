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

package com.google.ai.edge.gallery.ui.common.onboarding

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.dataStoreFile
import com.google.ai.edge.gallery.proto.OnboardingData
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Hilt module providing Proto DataStore dependencies for Onboarding. */
@Module
@InstallIn(SingletonComponent::class)
internal object OnboardingModule {
  @Provides
  @Singleton
  fun provideOnboardingDataStore(@ApplicationContext context: Context): DataStore<OnboardingData> {
    return DataStoreFactory.create(
      serializer = OnboardingSerializer,
      produceFile = { context.dataStoreFile("onboarding_data.pb") },
    )
  }
}
