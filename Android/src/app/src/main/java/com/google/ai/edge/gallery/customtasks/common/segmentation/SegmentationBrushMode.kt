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

package com.google.ai.edge.gallery.customtasks.common.segmentation

import com.google.mediapipe.tasks.vision.interactivesegmenter.Stroke

/** How a stroke changes the selected object of an interactive segmentation. */
enum class SegmentationBrushMode {
  /** Adds the stroked region to the selection. */
  ADD,
  /** Removes the stroked region from the selection. */
  SUBTRACT,
  /** Selects the object inside the stroked outline. */
  LASSO;

  /** The MediaPipe Interactive Segmenter brush mode of strokes drawn with this brush. */
  val mediaPipeBrushMode: Stroke.BrushMode
    get() =
      when (this) {
        ADD -> Stroke.BrushMode.POSITIVE
        SUBTRACT -> Stroke.BrushMode.NEGATIVE
        LASSO -> Stroke.BrushMode.LASSO
      }
}
