package androidx.camera.core.resolutionselector

import android.util.Size

class AspectRatioStrategy {
  companion object {
    val RATIO_4_3_FALLBACK_AUTO_STRATEGY = AspectRatioStrategy()
  }
}

class ResolutionStrategy(size: Size, fallbackRule: Int) {
  companion object {
    const val FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER = 1
    const val FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER = 2
  }
}

class ResolutionSelector {
  class Builder {
    fun setAspectRatioStrategy(strategy: AspectRatioStrategy): Builder = this
    fun setResolutionStrategy(strategy: ResolutionStrategy): Builder = this
    fun build(): ResolutionSelector = ResolutionSelector()
  }
}
