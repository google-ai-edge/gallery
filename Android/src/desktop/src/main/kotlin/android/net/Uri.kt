package android.net

import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class Uri private constructor(private val uriString: String) {
  private val parsedUri: URI? = runCatching { URI(uriString) }.getOrNull()

  val scheme: String?
    get() = parsedUri?.scheme

  val host: String?
    get() = parsedUri?.host

  val path: String?
    get() = parsedUri?.path ?: uriString

  val pathSegments: List<String>
    get() = path?.split('/')?.filter { it.isNotEmpty() } ?: emptyList()

  val lastPathSegment: String?
    get() = pathSegments.lastOrNull()

  fun getQueryParameter(key: String): String? {
    val query = parsedUri?.query ?: return null
    for (param in query.split('&')) {
      val pair = param.split('=', limit = 2)
      if (pair.isNotEmpty() && URLDecoder.decode(pair[0], StandardCharsets.UTF_8) == key) {
        return if (pair.size > 1) URLDecoder.decode(pair[1], StandardCharsets.UTF_8) else ""
      }
    }
    return null
  }

  fun getBooleanQueryParameter(key: String, defaultValue: Boolean): Boolean {
    val flag = getQueryParameter(key) ?: return defaultValue
    return flag.equals("true", ignoreCase = true) || flag == "1"
  }

  override fun toString(): String = uriString

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is Uri) return false
    return uriString == other.uriString
  }

  override fun hashCode(): Int = uriString.hashCode()

  fun buildUpon(): Builder = Builder(uriString)

  class Builder(private var base: String = "") {
    fun appendPath(pathSegment: String): Builder {
      base = if (base.endsWith("/")) base + pathSegment else "$base/$pathSegment"
      return this
    }
    fun appendQueryParameter(key: String, value: String): Builder {
      val sep = if (base.contains("?")) "&" else "?"
      base = "$base$sep$key=${Uri.encode(value)}"
      return this
    }
    fun build(): Uri = Uri.parse(base)
  }

  companion object {
    val EMPTY = Uri("")

    @JvmStatic
    fun parse(uriString: String): Uri = Uri(uriString)

    @JvmStatic
    fun fromFile(file: File): Uri = Uri(file.toURI().toString())

    @JvmStatic
    fun encode(s: String?): String =
      if (s == null) "" else URLEncoder.encode(s, StandardCharsets.UTF_8)

    @JvmStatic
    fun decode(s: String?): String =
      if (s == null) "" else URLDecoder.decode(s, StandardCharsets.UTF_8)
  }
}
