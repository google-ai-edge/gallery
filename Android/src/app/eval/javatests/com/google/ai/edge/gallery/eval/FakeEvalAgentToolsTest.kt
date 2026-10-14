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

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FakeEvalAgentToolsTest {

  private lateinit var tools: FakeEvalAgentTools
  private lateinit var context: Context

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    tools = FakeEvalAgentTools()
    tools.context = context
  }

  @Test
  fun testToolsInitialization() {
    assertThat(tools).isNotNull()
    assertThat(tools.taskId).isEqualTo("eval_task")

    // Verify tools can create view model
    val viewModel = tools.mcpManagerViewModel
    assertThat(viewModel).isNotNull()

    // Check mocked tools results default values
    assertThat(tools.runMcpToolResult).containsEntry("result", "runMcpTool success")
    assertThat(tools.runJsResult).containsEntry("result", "runJs success")
    assertThat(tools.runIntentResult).containsEntry("result", "runIntent success")

    // Run tool methods to get coverage
    val mcpRes = tools.runMcpTool("test", "test")
    assertThat(mcpRes).isEqualTo(tools.runMcpToolResult)

    val jsRes = tools.runJs("test", "test", "test")
    assertThat(jsRes).isEqualTo(tools.runJsResult)

    val intentRes = tools.runIntent("test", "test")
    assertThat(intentRes).isEqualTo(tools.runIntentResult)
  }
}
