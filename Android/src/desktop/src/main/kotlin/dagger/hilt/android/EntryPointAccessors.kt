package dagger.hilt.android

import android.content.Context

object EntryPointAccessors {
  @Suppress("UNCHECKED_CAST")
  fun <T> fromApplication(context: Context, entryPoint: Class<T>): T {
    return java.lang.reflect.Proxy.newProxyInstance(
      entryPoint.classLoader,
      arrayOf(entryPoint)
    ) { _, _, _ -> null } as T
  }
}
