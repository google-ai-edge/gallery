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
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig

private const val SUPERTONIC_THREADS = 8
private const val SUPERTONIC_SPEED = 1.0f
private const val SUPERTONIC_NUM_STEPS = 8
private const val SUPERTONIC_SPEAKER_ID = 6

internal class SherpaSupertonicTtsEngine(
  context: Context,
  onDownloadProgress: (TranslationTtsDownloadProgress) -> Unit = {},
) : SherpaTtsEngine<SupertonicSherpaPackage>(context, onDownloadProgress) {
  override val backend = SUPERTONIC_SHERPA_BACKEND
  override val revision = SUPERTONIC_SHERPA_PACKAGE_ID
  override val displayName = "Sherpa Supertonic"
  override val sampleRate = SUPERTONIC_SHERPA_SAMPLE_RATE
  override val logSettings = "threads=$SUPERTONIC_THREADS steps=$SUPERTONIC_NUM_STEPS"

  override suspend fun installPackage(
    context: Context,
    onProgress: (TranslationTtsDownloadProgress) -> Unit,
  ): SupertonicSherpaPackage =
    SherpaSupertonicPackageInstaller.ensureInstalled(context, onProgress)

  override fun packageRoot(installedPackage: SupertonicSherpaPackage) =
    installedPackage.rootDirectory

  override fun sherpaLanguage(normalizedLanguageTag: String): String =
    when (normalizedLanguageTag) {
      "en-us" -> "en"
      "es" -> "es"
      "fr-fr" -> "fr"
      "hi" -> "hi"
      "it" -> "it"
      "pt-br" -> "pt"
      else ->
        throw TranslationTtsSynthesisException(
          "Supertonic 3 does not support language tag: $normalizedLanguageTag"
        )
    }

  override fun generationConfig(
    installedPackage: SupertonicSherpaPackage,
    language: String,
  ) =
    GenerationConfig(
      speed = SUPERTONIC_SPEED,
      sid = SUPERTONIC_SPEAKER_ID,
      numSteps = SUPERTONIC_NUM_STEPS,
      extra = mapOf("lang" to language),
    )

  override fun createTts(
    installedPackage: SupertonicSherpaPackage,
    language: String,
  ): OfflineTts =
    OfflineTts(
      config =
        OfflineTtsConfig(
          model =
            OfflineTtsModelConfig(
              supertonic =
                OfflineTtsSupertonicModelConfig(
                  durationPredictor = installedPackage.durationPredictor.absolutePath,
                  textEncoder = installedPackage.textEncoder.absolutePath,
                  vectorEstimator = installedPackage.vectorEstimator.absolutePath,
                  vocoder = installedPackage.vocoder.absolutePath,
                  ttsJson = installedPackage.ttsJson.absolutePath,
                  unicodeIndexer = installedPackage.unicodeIndexer.absolutePath,
                  voiceStyle = installedPackage.voiceStyle.absolutePath,
                ),
              numThreads = SUPERTONIC_THREADS,
              debug = false,
              provider = "cpu",
            ),
          maxNumSentences = 1,
        )
    )
}
