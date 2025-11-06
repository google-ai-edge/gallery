/*
 * Copyright 2025 Neural Forge
 * Based on Google AI Edge Gallery
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

package com.neuralforge.mobile

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.neuralforge.mobile.ui.modelmanager.ModelManagerViewModel
import com.neuralforge.mobile.ui.navigation.GalleryNavHost

/** Top level composable representing the main screen of Neural Forge. */
@Composable
fun NeuralForgeApp(
  navController: NavHostController = rememberNavController(),
  modelManagerViewModel: ModelManagerViewModel,
) {
  GalleryNavHost(navController = navController, modelManagerViewModel = modelManagerViewModel)
}
