/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.ui.llmchat

import com.google.ai.edge.gallery.data.BuiltInTaskId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmChatModelHelperTest {
  @Test
  fun speculativeDecoding_translationForcesEnabledForSupportedModel() {
    assertTrue(
      shouldEnableSpeculativeDecoding(
        taskId = BuiltInTaskId.LLM_TRANSLATION,
        modelSupportsSpeculativeDecoding = true,
        capabilityTaskIds = emptyList(),
        configuredEnabled = false,
      )
    )
  }

  @Test
  fun speculativeDecoding_translationRemainsDisabledForUnsupportedModel() {
    assertFalse(
      shouldEnableSpeculativeDecoding(
        taskId = BuiltInTaskId.LLM_TRANSLATION,
        modelSupportsSpeculativeDecoding = false,
        capabilityTaskIds = listOf(BuiltInTaskId.LLM_TRANSLATION),
        configuredEnabled = true,
      )
    )
  }

  @Test
  fun speculativeDecoding_otherTasksRetainCapabilityAndSettingGates() {
    assertFalse(
      shouldEnableSpeculativeDecoding(
        taskId = BuiltInTaskId.LLM_CHAT,
        modelSupportsSpeculativeDecoding = true,
        capabilityTaskIds = listOf(BuiltInTaskId.LLM_CHAT),
        configuredEnabled = false,
      )
    )
    assertTrue(
      shouldEnableSpeculativeDecoding(
        taskId = BuiltInTaskId.LLM_CHAT,
        modelSupportsSpeculativeDecoding = true,
        capabilityTaskIds = listOf(BuiltInTaskId.LLM_CHAT),
        configuredEnabled = true,
      )
    )
  }
}
