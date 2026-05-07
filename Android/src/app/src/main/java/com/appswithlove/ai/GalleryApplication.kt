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

package com.appswithlove.ai

import android.app.Application
import androidx.appfunctions.service.AppFunctionConfiguration
import com.appswithlove.ai.customtasks.aijournal.worker.JournalSummarizationWorker
import com.appswithlove.ai.customtasks.emoji.EmojiAppFunction
import com.appswithlove.ai.customtasks.insurancecard.InsuranceCardAppFunction
import com.appswithlove.ai.data.DataStoreRepository
import com.appswithlove.ai.ui.theme.LocaleSettings
import com.appswithlove.ai.ui.theme.ThemeSettings
import com.appswithlove.updraft.Settings
import com.appswithlove.updraft.Updraft
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import javax.inject.Provider

@HiltAndroidApp
class GalleryApplication : Application(), AppFunctionConfiguration.Provider {

  @Inject lateinit var dataStoreRepository: DataStoreRepository
  @Inject lateinit var emojiAppFunction: Provider<EmojiAppFunction>
  @Inject lateinit var insuranceCardAppFunction: Provider<InsuranceCardAppFunction>

  override val appFunctionConfiguration: AppFunctionConfiguration
    get() = AppFunctionConfiguration.Builder()
      .addEnclosingClassFactory(EmojiAppFunction::class.java) { emojiAppFunction.get() }
      .addEnclosingClassFactory(InsuranceCardAppFunction::class.java) { insuranceCardAppFunction.get() }
      .build()

  override fun onCreate() {
    super.onCreate()

    // Load saved theme and locale.
    ThemeSettings.themeOverride.value = dataStoreRepository.readTheme()
    LocaleSettings.languageTag.value = dataStoreRepository.readLanguageTag()

    JournalSummarizationWorker.schedule(this)

    initUpdraft()
  }

  private fun initUpdraft() {
    val appKey = BuildConfig.UPDRAFT_APP_KEY
    val sdkKey = BuildConfig.UPDRAFT_SDK_KEY
    if (appKey.isEmpty() || sdkKey.isEmpty()) return

    val settings = Settings().apply {
      this.appKey = appKey
      this.sdkKey = sdkKey
      logLevel = Settings.LOG_LEVEL_ERROR
      showFeedbackAlert = false
      feedbackEnabled = false
    }
    Updraft.initialize(this, settings)
    Updraft.getInstance()?.start()
  }
}
