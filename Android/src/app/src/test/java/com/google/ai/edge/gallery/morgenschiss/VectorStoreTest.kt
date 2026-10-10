package com.google.ai.edge.gallery.morgenschiss

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VectorStoreTest {
  @Test
  fun packAndUnpackKeepTheVectorWithinOneStep() {
    val v = FloatArray(DIMS) { (it % 17 - 8) / 40f }
    val (q, s) = VectorStore.pack(v)
    val back = VectorStore.unpack(q, s)
    for (i in v.indices) assertTrue(abs(v[i] - back[i]) <= s / 127f)
  }

  @Test
  fun matchesTheServerFormula() {
    // server: value = q / 127 * s
    val q = byteArrayOf(127, -127, 0, 64)
    val v = VectorStore.unpack(q, 0.5f)
    assertEquals(0.5f, v[0], 1e-6f)
    assertEquals(-0.5f, v[1], 1e-6f)
    assertEquals(0f, v[2], 1e-6f)
    assertEquals(64 / 127f * 0.5f, v[3], 1e-6f)
  }
}
