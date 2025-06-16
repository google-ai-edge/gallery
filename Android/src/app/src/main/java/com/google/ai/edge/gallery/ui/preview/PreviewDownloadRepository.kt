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

import com.google.ai.edge.gallery.data.AGWorkInfo
import com.google.ai.edge.gallery.data.DownloadRepository
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelDownloadStatus
import java.util.UUID

class PreviewDownloadRepository : DownloadRepository {
  override fun downloadModel(
    model: Model,
    onStatusUpdated: (model: Model, status: ModelDownloadStatus) -> Unit,
  ) {}

  override fun cancelDownloadModel(model: Model) {}

  override fun cancelAll(models: List<Model>, onComplete: () -> Unit) {}

  override fun observerWorkerProgress(
    workerId: UUID,
    model: Model,
    onStatusUpdated: (model: Model, status: ModelDownloadStatus) -> Unit,
  ) {}

  override fun getEnqueuedOrRunningWorkInfos(): List<AGWorkInfo> {
    return listOf()
  }
}
