package android.content

class ContentValues {
  private val values = mutableMapOf<String, Any?>()

  fun put(key: String, value: String?) { values[key] = value }
  fun put(key: String, value: Long?) { values[key] = value }
  fun put(key: String, value: Int?) { values[key] = value }
  fun put(key: String, value: Boolean?) { values[key] = value }
  fun get(key: String): Any? = values[key]
}
