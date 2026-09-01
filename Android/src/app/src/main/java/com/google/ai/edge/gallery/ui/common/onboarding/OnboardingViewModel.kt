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

import androidx.datastore.core.DataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.proto.OnboardingData
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** ViewModel for managing onboarding dialog visited states per feature ID. */
@HiltViewModel
open class OnboardingViewModel
@Inject
constructor(private val onboardingDataStore: DataStore<OnboardingData>) : ViewModel() {

  /** Returns a [Flow] emitting whether the dialog for the given [featureId] has been visited. */
  open fun isVisitedFlow(featureId: String): Flow<Boolean> {
    return onboardingDataStore.data.map { data -> data.containsVisitedFeatureIds(featureId) }
  }

  /** Synchronously checks whether the dialog for the given [featureId] has been visited. */
  open fun isFeatureVisited(featureId: String): Boolean {
    return runBlocking { onboardingDataStore.data.first().containsVisitedFeatureIds(featureId) }
  }

  /** Marks the onboarding dialog for [featureId] as visited. */
  open fun markVisited(featureId: String) {
    viewModelScope.launch {
      onboardingDataStore.updateData { data ->
        if (data.containsVisitedFeatureIds(featureId)) {
          data
        } else {
          data.toBuilder().putVisitedFeatureIds(featureId, true).build()
        }
      }
    }
  }

  /** Resets the visited status for the given [featureId]. */
  open fun resetVisited(featureId: String) {
    viewModelScope.launch {
      onboardingDataStore.updateData { data ->
        if (data.containsVisitedFeatureIds(featureId)) {
          data.toBuilder().removeVisitedFeatureIds(featureId).build()
        } else {
          data
        }
      }
    }
  }
}
