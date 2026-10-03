package android.hardware.camera2

class CameraManager {
  val cameraIdList: Array<String> get() = emptyArray()
  fun getCameraCharacteristics(cameraId: String): CameraCharacteristics = CameraCharacteristics()
  fun setTorchMode(cameraId: String, enabled: Boolean) {}
}

class CameraCharacteristics {
  companion object {
    val FLASH_INFO_AVAILABLE = Key<Boolean>()
  }
  class Key<T>
  fun <T> get(key: Key<T>): T? = null
}
