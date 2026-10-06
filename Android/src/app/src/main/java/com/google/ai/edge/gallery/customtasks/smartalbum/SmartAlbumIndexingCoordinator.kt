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

package com.google.ai.edge.gallery.customtasks.smartalbum

/**
 * Coordinates indexing throughput with user interaction state.
 *
 * Pauses embedding inference when the user is actively scrolling the gallery or search results grid
 * to prevent GPU and thread contention from dropping UI frames.
 */
object SmartAlbumIndexingCoordinator {
  /** Indicates whether the gallery or search results grid is actively scrolling. */
  @Volatile var isScrollInProgress: Boolean = false

  /** Indicates whether the Smart Album gallery screen is currently in the foreground. */
  @Volatile var isGalleryVisible: Boolean = false
}
