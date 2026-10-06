package android.app

class UiModeManager {
  fun setApplicationNightMode(mode: Int) {}

  companion object {
    const val MODE_NIGHT_NO: Int = 1
    const val MODE_NIGHT_YES: Int = 2
    const val MODE_NIGHT_AUTO: Int = 0
  }
}
