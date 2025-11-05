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

package com.neuralforge.mobile.customtasks.examplecustomtask

import com.neuralforge.mobile.customtasks.common.CustomTask
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/**
 * A Hilt module that provides the `ExampleCustomTask` implementation.
 *
 * This module is crucial for integrating your custom task into the application's plugin system. By
 * using `@Provides` and `@IntoSet`, you are telling Hilt to add an instance of `ExampleCustomTask`
 * to a `Set<CustomTask>`, which the main app will use to discover all available custom tasks
 * without needing to know about each one individually.
 */
@Module
@InstallIn(SingletonComponent::class) // Or another component that fits your scope
internal object ExampleCustomTaskModule {
  /* Remove comment to enable the function to see this example custom task in action in the app.
  @Provides
  @IntoSet
  fun provideExampleCustomTask(): CustomTask {
    return ExampleCustomTask()
  }
  */
}
