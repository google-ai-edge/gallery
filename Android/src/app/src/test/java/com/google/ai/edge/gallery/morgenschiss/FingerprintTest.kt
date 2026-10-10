package com.google.ai.edge.gallery.morgenschiss

import org.junit.Assert.assertEquals
import org.junit.Test

class FingerprintTest {
  private fun pattern(size: Int) = ByteArray(size) { (it % 251).toByte() }

  private fun fp(size: Int): String {
    val data = pattern(size)
    return Fingerprint.compute(size.toLong()) { r ->
      data.copyOfRange(r.first.toInt(), (r.last + 1).toInt())
    }
  }

  // expected values computed with Interface's client/Apps/Shared/fileFingerprint.js
  @Test fun emptyFile() = assertEquals("9a271f2a916b0b6ee6cecb2426f0b3206ef074578be55d9bc94f6f3fe3ab86aa", fp(0))

  @Test fun smallFile() = assertEquals("689830a73afdfadfbda1768f5b2dcdd317f9f5574949461218588a4be8ce99f5", fp(10))

  @Test fun twelveMiBIsReadWhole() =
    assertEquals("2034e25c2889e3e81cbbb092f77d6f673b42e876f103ddd70805a972d4ba5216", fp(12 * 1024 * 1024))

  @Test fun largeFileUsesThreeParts() =
    assertEquals("c27c1f38663060c4c7a6c1022dd643e8c48cb0cce263e8e780bdf7ec3214326b", fp(13 * 1024 * 1024 + 7))
}
