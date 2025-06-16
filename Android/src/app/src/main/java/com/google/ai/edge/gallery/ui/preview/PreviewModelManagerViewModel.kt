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

package com.google.ai.edge.gallery.ui.preview

class PreviewModelManagerViewModel {}

// class PreviewModelManagerViewModel(context: Context) :
//   ModelManagerViewModel(
//     downloadRepository = PreviewDownloadRepository(),
//     // dataStoreRepository = PreviewDataStoreRepository(),
//     context = context,
//   ) {

//   init {
//     for ((index, task) in ALL_PREVIEW_TASKS.withIndex()) {
//       task.index = index
//       for (model in task.models) {
//         model.preProcess()
//       }
//     }

//     val modelDownloadStatus =
//       mapOf(
//         MODEL_TEST1.name to
//           ModelDownloadStatus(
//             status = ModelDownloadStatusType.IN_PROGRESS,
//             receivedBytes = 1234,
//             totalBytes = 3456,
//             bytesPerSecond = 2333,
//             remainingMs = 324,
//           ),
//         MODEL_TEST2.name to ModelDownloadStatus(status = ModelDownloadStatusType.SUCCEEDED),
//         MODEL_TEST3.name to
//           ModelDownloadStatus(
//             status = ModelDownloadStatusType.FAILED,
//             errorMessage = "Http code 404",
//           ),
//         MODEL_TEST4.name to ModelDownloadStatus(status = ModelDownloadStatusType.NOT_DOWNLOADED),
//       )
//     val newUiState =
//       ModelManagerUiState(
//         tasks = ALL_PREVIEW_TASKS,
//         modelDownloadStatus = modelDownloadStatus,
//         modelInitializationStatus = mapOf(),
//         selectedModel = MODEL_TEST2,
//       )
//     _uiState.update { newUiState }
//   }
// }
