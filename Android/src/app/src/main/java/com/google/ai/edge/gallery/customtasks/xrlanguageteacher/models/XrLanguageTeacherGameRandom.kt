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

/**
 * Deterministic xorshift64* pseudo-random generator shared by the XR Language Teacher study game
 * engines.
 *
 * Matches the iOS engines bit-for-bit so a given seed produces the same spawn sequence on both
 * platforms, which keeps seeded unit tests portable.
 *
 * @param seed Initial non-zero generator state.
 */
internal class XrLanguageTeacherGameRandom(seed: Long) {
  private var state = seed

  /** Returns the next 64-bit value (interpreted as unsigned by callers that need it). */
  fun nextLong(): Long {
    state = state xor (state ushr 12)
    state = state xor (state shl 25)
    state = state xor (state ushr 27)
    return state * MULTIPLIER
  }

  /** Returns a uniformly distributed float in `[lower, upper)` using 24 random bits. */
  fun nextFloat(lower: Float, upper: Float): Float {
    val unit = (nextLong() and FLOAT_MASK).toFloat() / FLOAT_DIVISOR
    return lower + unit * (upper - lower)
  }

  /** Returns an integer in `[0, bound)` using unsigned modulo of the next 64-bit value. */
  fun nextInt(bound: Int): Int = (nextLong().toULong() % bound.toULong()).toInt()

  /** Returns a Fisher-Yates shuffled copy of [items]. */
  fun <T> shuffled(items: List<T>): MutableList<T> {
    val copy = items.toMutableList()
    if (copy.size <= 1) return copy
    for (i in copy.size - 1 downTo 1) {
      val j = nextInt(i + 1)
      if (i != j) {
        val swap = copy[i]
        copy[i] = copy[j]
        copy[j] = swap
      }
    }
    return copy
  }

  private companion object {
    const val MULTIPLIER = 0x2545F4914F6CDD1DL
    const val FLOAT_MASK = 0x00FFFFFFL
    const val FLOAT_DIVISOR = 16_777_216f // 0x0100_0000
  }
}
