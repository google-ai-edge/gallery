package com.google.ai.edge.gallery.apiserver

import com.google.ai.edge.gallery.data.Model
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Mirrors [ModelManagerViewModel]'s downloaded-model list into a plain singleton.
 *
 * [LocalApiForegroundService] needs to resolve "which downloaded models exist" to pick one to
 * serve, but it can't inject [ModelManagerViewModel] directly -- that's a `@HiltViewModel`, which
 * requires a `ViewModelStoreOwner` a `Service` doesn't have. `ModelManagerViewModel` pushes its
 * already-computed downloaded-model list here instead of this cache owning any loading logic
 * itself.
 */
@Singleton
class ModelCatalogCache @Inject constructor() {
  private val _models = MutableStateFlow<List<Model>>(emptyList())
  val models: StateFlow<List<Model>> = _models.asStateFlow()

  fun update(models: List<Model>) {
    _models.value = models
  }
}
