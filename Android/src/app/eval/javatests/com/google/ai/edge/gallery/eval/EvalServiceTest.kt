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

import android.content.Intent
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Unit tests for [EvalService] to verify foreground service lifecycle actions. */
@RunWith(RobolectricTestRunner::class)
class EvalServiceTest {

  @Test
  fun onStartCommand_startServer_startsForeground() {
    val intent =
      Intent(ApplicationProvider.getApplicationContext(), EvalService::class.java).apply {
        action = EvalService.ACTION_START_SERVER
        putExtra("port", 8083)
        putExtra("model_path", "test_model.tflite")
        putExtra("support_image", true)
        putExtra("support_audio", true)
        putExtra("accelerator", "CPU")
      }

    val controller = Robolectric.buildService(EvalService::class.java, intent)
    controller.create().startCommand(0, 1)

    val service = controller.get()
    assertThat(service).isNotNull()
    val shadowService = shadowOf(service)
    assertThat(shadowService.lastForegroundNotificationId).isEqualTo(1)
    assertThat(shadowService.lastForegroundNotification).isNotNull()

    // Test onBind for coverage
    assertThat(service.onBind(intent)).isNull()

    // Test double start to cover "Server already running" branch
    controller.startCommand(0, 2)

    // Test onDestroy
    controller.destroy()
  }

  @org.robolectric.annotation.Config(sdk = [33])
  @Test
  fun onStartCommand_oldSdk_startsForeground() {
    val intent =
      Intent(ApplicationProvider.getApplicationContext(), EvalService::class.java).apply {
        action = null
        putExtra("port", 8083)
      }
    val controller = Robolectric.buildService(EvalService::class.java, intent)
    controller.create().startCommand(0, 1)

    val service = controller.get()
    val shadowService = shadowOf(service)
    assertThat(shadowService.lastForegroundNotificationId).isEqualTo(1)
    controller.destroy()
  }

  @Test
  fun onStartCommand_stopServer_stopsService() {
    val intent =
      Intent(ApplicationProvider.getApplicationContext(), EvalService::class.java).apply {
        action = EvalService.ACTION_STOP_SERVER
      }

    val controller = Robolectric.buildService(EvalService::class.java, intent)
    controller.create().startCommand(0, 1)

    val service = controller.get()
    assertThat(service).isNotNull()
    assertThat(shadowOf(service).isStoppedBySelf).isTrue()
  }

  @Test
  fun onStartCommand_nullAction_startsForeground() {
    val intent = Intent(ApplicationProvider.getApplicationContext(), EvalService::class.java)
    intent.putExtra("model_path", "/fake/path")
    intent.putExtra("support_audio", true)
    intent.putExtra("support_image", true)
    intent.putExtra("accelerator", "GPU")
    val controller = Robolectric.buildService(EvalService::class.java, intent)
    controller.create().startCommand(0, 1)

    val service = controller.get()
    assertThat(shadowOf(service).lastForegroundNotificationId).isEqualTo(1)
  }

  @Test
  @Config(sdk = [Build.VERSION_CODES.TIRAMISU])
  fun onStartCommand_olderSdk_startsForeground() {
    val intent =
      Intent(ApplicationProvider.getApplicationContext(), EvalService::class.java).apply {
        action = EvalService.ACTION_START_SERVER
      }
    val controller = Robolectric.buildService(EvalService::class.java, intent)
    controller.create().startCommand(0, 1)

    val service = controller.get()
    assertThat(shadowOf(service).lastForegroundNotificationId).isEqualTo(1)
  }

  @Test
  fun onDestroy_stopsServer() {
    val intent =
      Intent(ApplicationProvider.getApplicationContext(), EvalService::class.java).apply {
        action = EvalService.ACTION_START_SERVER
      }
    val controller = Robolectric.buildService(EvalService::class.java, intent)
    controller.create().startCommand(0, 1)

    // Trigger onDestroy
    controller.destroy()
    // It should not throw and server=null should be covered.
  }
}
