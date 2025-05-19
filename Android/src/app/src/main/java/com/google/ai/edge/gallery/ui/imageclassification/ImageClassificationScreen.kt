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

package com.google.ai.edge.gallery.ui.imageclassification

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.ai.edge.gallery.ui.ViewModelProvider
import com.google.ai.edge.gallery.ui.common.chat.ChatInputType
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageImage
import com.google.ai.edge.gallery.ui.common.chat.ChatView
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import kotlinx.serialization.Serializable

/** Navigation destination data */
object ImageClassificationDestination {
  @Serializable
  val route = "ImageClassificationRoute"
}

@Composable
fun ImageClassificationScreen(
  modelManagerViewModel: ModelManagerViewModel,
  navigateUp: () -> Unit,
  modifier: Modifier = Modifier,
  viewModel: ImageClassificationViewModel = viewModel(
    factory = ViewModelProvider.Factory
  ),
) {
  val context = LocalContext.current
  val primaryColor = MaterialTheme.colorScheme.primary

  ChatView(
    task = viewModel.task,
    viewModel = viewModel,
    modelManagerViewModel = modelManagerViewModel,
    onSendMessage = { model, messages ->
      val message = messages[0]
      viewModel.addMessage(
        model = model,
        message = message,
      )
      if (message is ChatMessageImage) {
        viewModel.generateResponse(
          context = context,
          model = model,
          input = message.bitmap,
          primaryColor = primaryColor,
        )
      }
    },
    onStreamImageMessage = { model, message ->
      viewModel.generateStreamingResponse(
        context = context,
        model = model,
        input = message.bitmap,
        primaryColor = primaryColor,
      )
    },
    onRunAgainClicked = { model, message ->
      viewModel.runAgain(
        context = context,
        model = model,
        message = message,
        primaryColor = primaryColor,
      )
    },
    onBenchmarkClicked = { model, message, warmUpIterations, benchmarkIterations ->
      viewModel.benchmark(
        context = context,
        model = model,
        message = message,
        warmupCount = warmUpIterations,
        iterations = benchmarkIterations,
        primaryColor = primaryColor,
      )
    },
    navigateUp = navigateUp,
    modifier = modifier,
    chatInputType = ChatInputType.IMAGE,
  )
}
