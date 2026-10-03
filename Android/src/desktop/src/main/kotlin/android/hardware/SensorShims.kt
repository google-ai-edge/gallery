package android.hardware

class Sensor {
  val type: Int = TYPE_ACCELEROMETER

  companion object {
    const val TYPE_ACCELEROMETER = 1
    const val TYPE_GYROSCOPE = 4
  }
}

class SensorEvent(val values: FloatArray = FloatArray(3), val sensor: Sensor? = Sensor())

interface SensorEventListener {
  fun onSensorChanged(event: SensorEvent?)
  fun onAccuracyChanged(sensor: Sensor?, accuracy: Int)
}

class SensorManager {
  companion object {
    const val SENSOR_DELAY_NORMAL = 3
    const val SENSOR_DELAY_UI = 2
    const val SENSOR_DELAY_GAME = 1
    const val SENSOR_DELAY_FASTEST = 0
  }

  fun getDefaultSensor(type: Int): Sensor? = Sensor()
  fun registerListener(listener: SensorEventListener?, sensor: Sensor?, samplingPeriodUs: Int): Boolean = false
  fun unregisterListener(listener: SensorEventListener?) {}
}
