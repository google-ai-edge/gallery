package android.os

object Build {
  const val MODEL: String = "Windows PC"
  const val MANUFACTURER: String = "Microsoft"
  const val ID: String = "Windows"
  const val HARDWARE: String = "x86_64"
  const val BOARD: String = "PC"
  const val SOC_MODEL: String = "Intel/AMD"
  const val DEVICE: String = "Desktop"
  const val FINGERPRINT: String = "Windows"

  object VERSION {
    const val SDK_INT: Int = 35
    const val RELEASE: String = "15"
  }

  object VERSION_CODES {
    const val O = 26
    const val Q = 29
    const val R = 30
    const val S = 31
    const val TIRAMISU = 33
    const val UPSIDE_DOWN_CAKE = 34
    const val VANILLA_ICE_CREAM = 35
  }
}
