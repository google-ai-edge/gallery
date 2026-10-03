package androidx.core.os

import android.os.Bundle

fun bundleOf(vararg pairs: Pair<String, Any?>): Bundle {
  val b = Bundle()
  for ((k, v) in pairs) {
    when (v) {
      is String -> b.putString(k, v)
      is Int -> b.putInt(k, v)
      is Long -> b.putLong(k, v)
      is Boolean -> b.putBoolean(k, v)
      is Double -> b.putDouble(k, v)
    }
  }
  return b
}
