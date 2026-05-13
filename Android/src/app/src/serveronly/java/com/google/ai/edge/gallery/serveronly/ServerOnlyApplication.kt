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

package com.google.ai.edge.gallery.serveronly

import android.app.Application

/**
 * Slim [Application] for the standalone server APK.
 *
 * Intentionally does NOT extend `GalleryApplication` and does NOT initialise Firebase, Hilt, or
 * the proto DataStore — none of those are needed to run the embedded HTTP server. Keeping this
 * class minimal also avoids dragging the chat-UI dependency graph into the headless flavor.
 */
class ServerOnlyApplication : Application()
