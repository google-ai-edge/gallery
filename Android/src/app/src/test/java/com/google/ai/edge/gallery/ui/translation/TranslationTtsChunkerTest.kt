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

package com.google.ai.edge.gallery.ui.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationTtsChunkerTest {
  @Test
  fun emitsSentenceAsSoonAsTerminatorArrives() {
    val chunker = TranslationTtsChunker()

    assertEquals(listOf("This is ready."), chunker.append("This is ready."))
  }

  @Test
  fun waitsForLongerPhraseBeforeWhitespaceFallback() {
    val chunker = TranslationTtsChunker()

    val chunks = chunker.append("This translation is now long enough to begin speaking")

    assertEquals(listOf("This translation is now long enough to"), chunks)
    assertEquals(listOf("begin speaking"), chunker.flush())
  }

  @Test
  fun emitsNaturalClauseAfterMinimumLength() {
    val chunker = TranslationTtsChunker()

    assertTrue(chunker.append("One two three four five six").isEmpty())
    assertEquals(
      listOf("One two three four five six,"),
      chunker.append(", and then"),
    )
    assertEquals(listOf("and then"), chunker.flush())
  }

  @Test
  fun waitsForBreakAfterLongUnbrokenText() {
    val chunker = TranslationTtsChunker()

    assertTrue(chunker.append("abcdefghijklmnopqrstuvwxyzabcdefghij").isEmpty())
    assertEquals(
      listOf("abcdefghijklmnopqrstuvwxyzabcdefghij"),
      chunker.append(" next words continue"),
    )
    assertEquals(listOf("next words continue"), chunker.flush())
  }

  @Test
  fun keepsShortUnpunctuatedTextUntilFlush() {
    val chunker = TranslationTtsChunker()

    assertTrue(chunker.append("Short phrase").isEmpty())
    assertEquals(listOf("Short phrase"), chunker.flush())
  }
}
