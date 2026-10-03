package androidx.browser.customtabs

import android.content.Intent

class CustomTabsIntent(val intent: Intent = Intent()) {
  class Builder {
    fun build(): CustomTabsIntent = CustomTabsIntent()
  }
}
