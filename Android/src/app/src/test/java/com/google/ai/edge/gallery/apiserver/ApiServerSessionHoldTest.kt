package com.google.ai.edge.gallery.apiserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApiServerSessionHoldTest {
  @Test
  fun `heldModelName defaults to null`() {
    assertNull(ApiServerSessionHold().heldModelName)
  }

  @Test
  fun `heldModelName can be set and read back`() {
    val hold = ApiServerSessionHold()
    hold.heldModelName = "Gemma-4-E2B-it"
    assertEquals("Gemma-4-E2B-it", hold.heldModelName)
  }

  @Test
  fun `heldModelName can be cleared`() {
    val hold = ApiServerSessionHold()
    hold.heldModelName = "Gemma-4-E2B-it"
    hold.heldModelName = null
    assertNull(hold.heldModelName)
  }
}
