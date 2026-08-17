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
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig

private const val SHERPA_TTS_THREADS = 8
private const val SHERPA_TTS_SPEED = 1.0f

internal class SherpaKokoroTtsEngine(
  context: Context,
  onDownloadProgress: (TranslationTtsDownloadProgress) -> Unit = {},
) : SherpaTtsEngine<KokoroSherpaPackage>(context, onDownloadProgress) {
  override val backend = KOKORO_SHERPA_BACKEND
  override val revision = KOKORO_SHERPA_PACKAGE_ID
  override val displayName = "Sherpa Kokoro"
  override val sampleRate = KOKORO_SHERPA_SAMPLE_RATE
  override val logSettings = "threads=$SHERPA_TTS_THREADS speed=$SHERPA_TTS_SPEED"

  override suspend fun installPackage(
    context: Context,
    onProgress: (TranslationTtsDownloadProgress) -> Unit,
  ): KokoroSherpaPackage = SherpaKokoroPackageInstaller.ensureInstalled(context, onProgress)

  override fun packageRoot(installedPackage: KokoroSherpaPackage) =
    installedPackage.rootDirectory

  override fun configKey(language: String) = language

  override fun generationConfig(
    installedPackage: KokoroSherpaPackage,
    language: String,
  ): GenerationConfig {
    val voice =
      SherpaKokoroVoiceSelector.select(language, installedPackage.voiceConfigs)
        ?: throw TranslationTtsSynthesisException(
          "Translation TTS does not support language tag: $language"
        )
    return GenerationConfig(
      speed = SHERPA_TTS_SPEED,
      sid = voice.speakerId,
    )
  }

  override fun createTts(installedPackage: KokoroSherpaPackage, language: String): OfflineTts {
    val voice =
      SherpaKokoroVoiceSelector.select(language, installedPackage.voiceConfigs)
        ?: throw TranslationTtsSynthesisException(
          "Translation TTS does not support language tag: $language"
        )
    return OfflineTts(
      config =
        OfflineTtsConfig(
          model =
            OfflineTtsModelConfig(
              kokoro =
                OfflineTtsKokoroModelConfig(
                  model = installedPackage.modelFile.absolutePath,
                  voices = installedPackage.voicesFile.absolutePath,
                  tokens = installedPackage.tokensFile.absolutePath,
                  dataDir = installedPackage.espeakDataDirectory.absolutePath,
                  dictDir = installedPackage.dictionaryDirectory.absolutePath,
                  lang = voice.espeakVoice,
                ),
              numThreads = SHERPA_TTS_THREADS,
              debug = false,
              provider = "cpu",
            ),
          maxNumSentences = 1,
        )
    )
  }
}
