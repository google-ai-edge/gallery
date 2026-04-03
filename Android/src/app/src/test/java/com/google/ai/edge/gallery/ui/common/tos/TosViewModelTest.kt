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

package com.google.ai.edge.gallery.ui.common.tos

import com.google.ai.edge.gallery.data.DataStoreRepository
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TosViewModelTest {

  private lateinit var repository: DataStoreRepository
  private lateinit var viewModel: TosViewModel

  @Before
  fun setUp() {
    repository = mockk()
    viewModel = TosViewModel(repository)
  }

  // ---------------------------------------------------------------------------
  // getIsTosAccepted
  // ---------------------------------------------------------------------------

  @Test
  fun `getIsTosAccepted returns false when repository reports not accepted`() {
    every { repository.isTosAccepted() } returns false
    assertFalse(viewModel.getIsTosAccepted())
  }

  @Test
  fun `getIsTosAccepted returns true when repository reports accepted`() {
    every { repository.isTosAccepted() } returns true
    assertTrue(viewModel.getIsTosAccepted())
  }

  // ---------------------------------------------------------------------------
  // acceptTos
  // ---------------------------------------------------------------------------

  @Test
  fun `acceptTos delegates to repository`() {
    justRun { repository.acceptTos() }
    viewModel.acceptTos()
    verify(exactly = 1) { repository.acceptTos() }
  }

  @Test
  fun `acceptTos does not affect getIsGemmaTermsOfUseAccepted`() {
    justRun { repository.acceptTos() }
    every { repository.isGemmaTermsOfUseAccepted() } returns false

    viewModel.acceptTos()

    assertFalse(viewModel.getIsGemmaTermsOfUseAccepted())
    // acceptTos must not call acceptGemmaTermsOfUse as a side-effect
    verify(exactly = 0) { repository.acceptGemmaTermsOfUse() }
  }

  // ---------------------------------------------------------------------------
  // getIsGemmaTermsOfUseAccepted
  // ---------------------------------------------------------------------------

  @Test
  fun `getIsGemmaTermsOfUseAccepted returns false when repository reports not accepted`() {
    every { repository.isGemmaTermsOfUseAccepted() } returns false
    assertFalse(viewModel.getIsGemmaTermsOfUseAccepted())
  }

  @Test
  fun `getIsGemmaTermsOfUseAccepted returns true when repository reports accepted`() {
    every { repository.isGemmaTermsOfUseAccepted() } returns true
    assertTrue(viewModel.getIsGemmaTermsOfUseAccepted())
  }

  // ---------------------------------------------------------------------------
  // acceptGemmaTermsOfUse
  // ---------------------------------------------------------------------------

  @Test
  fun `acceptGemmaTermsOfUse delegates to repository`() {
    justRun { repository.acceptGemmaTermsOfUse() }
    viewModel.acceptGemmaTermsOfUse()
    verify(exactly = 1) { repository.acceptGemmaTermsOfUse() }
  }

  @Test
  fun `acceptGemmaTermsOfUse does not affect getIsTosAccepted`() {
    justRun { repository.acceptGemmaTermsOfUse() }
    every { repository.isTosAccepted() } returns false

    viewModel.acceptGemmaTermsOfUse()

    assertFalse(viewModel.getIsTosAccepted())
    verify(exactly = 0) { repository.acceptTos() }
  }

  // ---------------------------------------------------------------------------
  // Both ToS states independent
  // ---------------------------------------------------------------------------

  @Test
  fun `TOS and Gemma terms are independent of each other`() {
    every { repository.isTosAccepted() } returns true
    every { repository.isGemmaTermsOfUseAccepted() } returns false

    assertTrue(viewModel.getIsTosAccepted())
    assertFalse(viewModel.getIsGemmaTermsOfUseAccepted())
  }
}
