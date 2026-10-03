package android.app

class ActivityManager {
  class MemoryInfo {
    var totalMem: Long = 16L * 1024 * 1024 * 1024
    var availMem: Long = 8L * 1024 * 1024 * 1024
    var advertisedMem: Long = 16L * 1024 * 1024 * 1024
    var lowMemory: Boolean = false
  }

  fun getMemoryInfo(outInfo: MemoryInfo) {
    try {
      val osBean = java.lang.management.ManagementFactory.getOperatingSystemMXBean()
      if (osBean is com.sun.management.OperatingSystemMXBean) {
        val total = osBean.totalMemorySize
        val free = osBean.freeMemorySize
        outInfo.totalMem = total
        outInfo.advertisedMem = total
        outInfo.availMem = free
        outInfo.lowMemory = (free < 1L * 1024 * 1024 * 1024)
        return
      }
    } catch (_: Throwable) {}
    outInfo.totalMem = 16L * 1024 * 1024 * 1024
    outInfo.availMem = 8L * 1024 * 1024 * 1024
    outInfo.advertisedMem = 16L * 1024 * 1024 * 1024
    outInfo.lowMemory = false
  }
}
