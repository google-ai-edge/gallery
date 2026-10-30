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

package com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models

import kotlin.math.max

/**
 * Tracks which words of the speech recognizer's cumulative transcript of the current utterance
 * segment a study game already used, so that each spoken word only matches once.
 */
class XrLanguageTeacherTranscriptCursor {
  private var consumedTokenCount = 0
  private var consumedScalarCount = 0
  private var latestSeenTokenCount = 0
  private var latestSeenScalarCount = 0

  /** Starts over when the speech recognizer starts a new utterance segment. */
  fun reset() {
    consumedTokenCount = 0
    consumedScalarCount = 0
    latestSeenTokenCount = 0
    latestSeenScalarCount = 0
  }

  /**
   * Marks the words seen so far as used after a speaking pause, so stale unmatched words can't
   * match later targets.
   */
  fun consumePending() {
    consumedTokenCount = max(consumedTokenCount, latestSeenTokenCount)
    consumedScalarCount = max(consumedScalarCount, latestSeenScalarCount)
  }

  /**
   * Returns the words of the cumulative [transcript] that weren't used yet (empty when there are
   * none), and remembers how much of it was seen.
   */
  fun takeUnconsumedText(transcript: String): String {
    val extracted =
      XrLanguageTeacherSpeechMatcher.extractUnconsumedTranscript(
        transcript = transcript,
        consumedTokenCount = consumedTokenCount,
        consumedScalarCount = consumedScalarCount,
      )
    consumedTokenCount = extracted.consumedTokenCount
    consumedScalarCount = extracted.consumedScalarCount
    latestSeenTokenCount = extracted.totalTokens
    latestSeenScalarCount = extracted.totalScalars
    return extracted.unconsumedText
  }

  /** Marks the whole transcript last passed to [takeUnconsumedText] as used, after it matched. */
  fun consumeSeen() {
    consumedTokenCount = latestSeenTokenCount
    consumedScalarCount = latestSeenScalarCount
  }
}
