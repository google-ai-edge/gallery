package android.icu.text

import java.util.Locale

class CompactDecimalFormat {
  enum class CompactStyle {
    SHORT,
    LONG,
  }

  fun format(number: Long): String {
    return when {
      number >= 1_000_000_000L -> "%.1fB".format(number / 1_000_000_000.0)
      number >= 1_000_000L -> "%.1fM".format(number / 1_000_000.0)
      number >= 1_000L -> "%.1fK".format(number / 1_000.0)
      else -> number.toString()
    }
  }

  fun format(number: Double): String = format(number.toLong())

  companion object {
    @JvmStatic
    fun getInstance(locale: Locale, style: CompactStyle): CompactDecimalFormat = CompactDecimalFormat()
  }
}
