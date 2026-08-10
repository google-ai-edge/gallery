/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.ui.translation

import android.content.Context
import com.google.ai.edge.gallery.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TranslationTtsReadinessChecker
@Inject
constructor(@param:ApplicationContext private val appContext: Context) {
  internal suspend fun check(model: TranslationTtsModel): TranslationTtsReadiness {
    if (!BuildConfig.TRANSLATION_TTS_SHERPA_ENABLED || model == TranslationTtsModel.SYSTEM) {
      return TranslationTtsReadiness(model = model, isReady = true, preferSherpa = false)
    }

    val installed =
      withContext(Dispatchers.IO) {
        TranslationTtsModelRepository.isInstalled(context = appContext, model = model)
      }
    return TranslationTtsReadiness(
      model = model,
      isReady = installed,
      preferSherpa = installed,
    )
  }
}

internal data class TranslationTtsReadiness(
  val model: TranslationTtsModel,
  val isReady: Boolean,
  val preferSherpa: Boolean,
)
