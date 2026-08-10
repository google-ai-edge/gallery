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

package com.google.ai.edge.gallery.ui.translation

import android.content.Context
import android.util.Log

private const val TAG = "AGTranslationTtsStore"

internal object TranslationTtsEngineStore {
  private val lock = Any()
  private val engines = mutableMapOf<TranslationTtsModel, TranslationTtsEngine>()

  fun get(
    context: Context,
    model: TranslationTtsModel = TranslationTtsModel.DEFAULT,
  ): TranslationTtsEngine =
    synchronized(lock) {
      engines.getOrPut(model) {
        when (model) {
          TranslationTtsModel.SYSTEM -> error("Android system TTS does not use a Sherpa engine.")
          TranslationTtsModel.KOKORO -> SherpaKokoroTtsEngine(context.applicationContext)
          TranslationTtsModel.SUPERTONIC_3 ->
            SherpaSupertonicTtsEngine(context.applicationContext)
        }
      }
    }

  fun release(model: TranslationTtsModel) {
    synchronized(lock) { engines.remove(model) }?.release()
  }

  suspend fun initializeIfInstalled(
    context: Context,
    model: TranslationTtsModel = TranslationTtsModel.DEFAULT,
    ttsModelRepository: TranslationTtsModelRepository,
  ) {
    val appContext = context.applicationContext
    if (model == TranslationTtsModel.SYSTEM) {
      Log.i(TAG, "outcome=initialization_skipped_system_voice")
      return
    }
    if (!ttsModelRepository.isInstalled(model)) {
      Log.i(TAG, "outcome=initialization_skipped_package_missing")
      return
    }
    get(appContext, model).preload()
    Log.i(TAG, "outcome=initialization_completed")
  }
}
