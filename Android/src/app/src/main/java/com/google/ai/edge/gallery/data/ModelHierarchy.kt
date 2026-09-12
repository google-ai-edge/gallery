/*
 * Copyright 2026 Google LLC
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

package com.google.ai.edge.gallery.data

/**
 * Model family hierarchy and variant configuration.
 *
 * @property parentModelName The name of the parent model that this model is a variant of.
 * @property variantLabel The label of the model variant.
 */
data class ModelHierarchy(val parentModelName: String? = null, val variantLabel: String? = null) {
  /** Indicates whether this model configuration represents a variant of a parent model. */
  val isVariant: Boolean
    get() = !parentModelName.isNullOrEmpty()
}
