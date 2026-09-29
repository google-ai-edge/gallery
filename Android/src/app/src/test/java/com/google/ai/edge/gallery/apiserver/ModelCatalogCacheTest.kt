package com.google.ai.edge.gallery.apiserver

import com.google.ai.edge.gallery.data.Model
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogCacheTest {
  @Test
  fun `models starts empty`() {
    assertTrue(ModelCatalogCache().models.value.isEmpty())
  }

  @Test
  fun `update replaces the model list`() {
    val cache = ModelCatalogCache()
    val models = listOf(Model(name = "model-a"), Model(name = "model-b"))

    cache.update(models)

    assertEquals(models, cache.models.value)
  }

  @Test
  fun `a later update replaces, not appends`() {
    val cache = ModelCatalogCache()
    cache.update(listOf(Model(name = "model-a")))

    cache.update(listOf(Model(name = "model-b")))

    assertEquals(listOf(Model(name = "model-b")), cache.models.value)
  }
}
