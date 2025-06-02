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

package com.google.ai.edge.gallery.ui

import android.app.Application
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.google.ai.edge.gallery.GalleryApplication
import com.google.ai.edge.gallery.data.TASK_LLM_CHAT // Import TASK_LLM_CHAT
import com.google.ai.edge.gallery.ui.imageclassification.ImageClassificationViewModel
import com.google.ai.edge.gallery.ui.imagegeneration.ImageGenerationViewModel
import com.google.ai.edge.gallery.ui.llmchat.LlmChatViewModel
// import com.google.ai.edge.gallery.ui.llmchat.LlmAskImageViewModel // Removed
// import com.google.ai.edge.gallery.ui.llmsingleturn.LlmSingleTurnViewModel // Removed
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.gallery.ui.textclassification.TextClassificationViewModel
import com.google.ai.edge.gallery.ui.userprofile.UserProfileViewModel
import com.google.ai.edge.gallery.ui.persona.PersonaViewModel
import com.google.ai.edge.gallery.ui.conversationhistory.ConversationHistoryViewModel // Added import

object ViewModelProvider {
  val Factory = viewModelFactory {
    // Initializer for ModelManagerViewModel.
    initializer {
      val downloadRepository = galleryApplication().container.downloadRepository
      val dataStoreRepository = galleryApplication().container.dataStoreRepository
      ModelManagerViewModel(
        downloadRepository = downloadRepository,
        dataStoreRepository = dataStoreRepository,
        context = galleryApplication().container.context,
      )
    }

    // Initializer for TextClassificationViewModel
    initializer {
      TextClassificationViewModel()
    }

    // Initializer for ImageClassificationViewModel
    initializer {
      ImageClassificationViewModel()
    }

    // Initializer for LlmChatViewModel.
    initializer {
      val dataStoreRepository = galleryApplication().container.dataStoreRepository
      LlmChatViewModel(dataStoreRepository = dataStoreRepository, curTask = TASK_LLM_CHAT)
    }

    // Initializer for LlmSingleTurnViewModel.. - REMOVED
    // Initializer for LlmAskImageViewModel. - REMOVED

    // Initializer for ImageGenerationViewModel.
    initializer {
      ImageGenerationViewModel()
    }

    // Initializer for UserProfileViewModel.
    initializer {
      val dataStoreRepository = galleryApplication().container.dataStoreRepository
      UserProfileViewModel(dataStoreRepository = dataStoreRepository)
    }

    // Initializer for PersonaViewModel.
    initializer {
      val dataStoreRepository = galleryApplication().container.dataStoreRepository
      PersonaViewModel(dataStoreRepository = dataStoreRepository)
    }

    // Initializer for ConversationHistoryViewModel.
    initializer {
      val dataStoreRepository = galleryApplication().container.dataStoreRepository
      ConversationHistoryViewModel(dataStoreRepository = dataStoreRepository)
    }
  }
}

/**
 * Extension function to queries for [Application] object and returns an instance of
 * [GalleryApplication].
 */
fun CreationExtras.galleryApplication(): GalleryApplication =
  (this[AndroidViewModelFactory.APPLICATION_KEY] as GalleryApplication)
