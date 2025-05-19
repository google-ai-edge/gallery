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

package com.google.ai.edge.gallery.ui.textclassification

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.ai.edge.gallery.ui.ViewModelProvider
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageText
import com.google.ai.edge.gallery.ui.common.chat.ChatView
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import kotlinx.serialization.Serializable

/** Navigation destination data */
object TextClassificationDestination {
  @Serializable
  val route = "TextClassificationRoute"
}

@Composable
fun TextClassificationScreen(
  modelManagerViewModel: ModelManagerViewModel,
  navigateUp: () -> Unit,
  modifier: Modifier = Modifier,
  viewModel: TextClassificationViewModel = viewModel(
    factory = ViewModelProvider.Factory
  ),
) {
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
      if (message is ChatMessageText) {
        modelManagerViewModel.addTextInputHistory(message.content)
        viewModel.generateResponse(
          model = model,
          input = message.content,
        )
      }
    },
    onRunAgainClicked = { model, message ->
      viewModel.runAgain(model = model, message = message)
    },
    onBenchmarkClicked = { model, message, warmUpIterations, benchmarkIterations ->
      viewModel.benchmark(
        model = model,
        message = message,
        warmupCount = warmUpIterations,
        itertations = benchmarkIterations,
      )
    },
    navigateUp = navigateUp,
    modifier = modifier
  )
}
