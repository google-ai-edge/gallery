package android.os

import java.io.Serializable

class Bundle : Serializable {
  private val map = mutableMapOf<String, Any?>()

  fun putString(key: String, value: String?) {
    map[key] = value
  }

  fun getString(key: String): String? = map[key] as? String

  fun getString(key: String, defaultValue: String): String = (map[key] as? String) ?: defaultValue

  fun putStringArrayList(key: String, value: ArrayList<String>?) {
    map[key] = value
  }

  fun getStringArrayList(key: String): ArrayList<String>? =
    (map[key] as? ArrayList<String>) ?: (map[key] as? List<String>)?.let { ArrayList(it) }

  fun putInt(key: String, value: Int) {
    map[key] = value
  }

  fun getInt(key: String, defaultValue: Int = 0): Int = (map[key] as? Int) ?: defaultValue

  fun putLong(key: String, value: Long) {
    map[key] = value
  }

  fun getLong(key: String, defaultValue: Long = 0L): Long = (map[key] as? Long) ?: defaultValue

  fun putBoolean(key: String, value: Boolean) {
    map[key] = value
  }

  fun getBoolean(key: String, defaultValue: Boolean = false): Boolean = (map[key] as? Boolean) ?: defaultValue

  fun putDouble(key: String, value: Double) {
    map[key] = value
  }

  fun getDouble(key: String, defaultValue: Double = 0.0): Double = (map[key] as? Double) ?: defaultValue

  fun containsKey(key: String): Boolean = map.containsKey(key)

  fun keySet(): Set<String> = map.keys

  fun get(key: String): Any? = map[key]

  fun clear() = map.clear()

  fun isEmpty(): Boolean = map.isEmpty()

  fun size(): Int = map.size
}
