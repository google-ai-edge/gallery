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

package com.google.ai.edge.gallery.services.asr

import dagger.MapKey

/**
 * Hilt map key that registers an [AsrService] engine under its [AsrEngineType].
 *
 * Engines are collected into a `Map<AsrEngineType, AsrService>`, so adding an engine does not
 * change its consumers. To add an engine:
 * 1. Add a value to [AsrEngineType], including its [AsrEngineType.fallback], and fix every
 *    exhaustive `when` the compiler flags.
 * 2. Implement [AsrService].
 * 3. Contribute it from a Hilt module with `@Provides @Singleton @IntoMap @AsrEngineKey(...)`.
 */
@MapKey
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class AsrEngineKey(val value: AsrEngineType)
