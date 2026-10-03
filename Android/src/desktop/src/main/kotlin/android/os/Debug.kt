package android.os

object Debug {
  @JvmStatic
  fun getPss(): Long {
    val runtime = Runtime.getRuntime()
    return (runtime.totalMemory() - runtime.freeMemory()) / 1024L
  }
}
