/*
 * Copyright 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.ai.edge.gallery.eval

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

/** Unit tests for [EvalReceiver] to verify broadcast routing to [EvalService]. */
@RunWith(RobolectricTestRunner::class)
class EvalReceiverTest {

  @Test
  fun onReceive_forwardsIntentToService() {
    val context = ApplicationProvider.getApplicationContext<Application>()
    val receiver = EvalReceiver()

    val intent = Intent(EvalService.ACTION_START_SERVER).apply { putExtra("port", 9090) }

    receiver.onReceive(context, intent)

    val shadowApp = Shadows.shadowOf(context)
    val startedIntent: Intent? = shadowApp.nextStartedService

    assertWithMessage("startedIntent").that(startedIntent).isNotNull()
    val nonNullIntent: Intent = startedIntent!!
    assertWithMessage("className")
      .that(nonNullIntent.component?.className)
      .isEqualTo(EvalService::class.java.name)
    assertWithMessage("action")
      .that(nonNullIntent.action)
      .isEqualTo(EvalService.ACTION_START_SERVER)
    assertWithMessage("port").that(nonNullIntent.getIntExtra("port", 0)).isEqualTo(9090)
  }
}
