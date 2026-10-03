package android.util

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Desktop replacement for android.util.Log. Writes to stdout/stderr (useful when run from a
 * terminal) and to `%LOCALAPPDATA%\AIEdgeGallery\logs\app.log`, because the packaged Windows app is
 * a GUI-subsystem process with no console.
 */
object Log {
  private val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
  private const val MAX_LOG_BYTES = 5L * 1024 * 1024

  private val logFile: File? by lazy {
    runCatching {
      val base = System.getenv("LOCALAPPDATA")
        ?: (System.getProperty("user.home") + File.separator + "AppData" + File.separator + "Local")
      val dir = File(base, "AIEdgeGallery" + File.separator + "logs").apply { mkdirs() }
      val file = File(dir, "app.log")
      // Keep one previous log; start fresh when the current one grows too large.
      if (file.length() > MAX_LOG_BYTES) {
        val old = File(dir, "app.previous.log")
        old.delete()
        file.renameTo(old)
      }
      file
    }.getOrNull()
  }

  private fun write(level: String, tag: String, msg: String, tr: Throwable?, err: Boolean) {
    val line = "[$tag] $level: $msg"
    val stream = if (err) System.err else System.out
    stream.println(line)
    tr?.printStackTrace(stream)
    val file = logFile ?: return
    runCatching {
      val sb = StringBuilder()
      sb.append(LocalDateTime.now().format(timeFormat)).append(' ')
        .append('[').append(Thread.currentThread().name).append("] ")
        .append(line).append(System.lineSeparator())
      if (tr != null) {
        val sw = StringWriter()
        tr.printStackTrace(PrintWriter(sw))
        sb.append(sw)
      }
      synchronized(this) { file.appendText(sb.toString()) }
    }
  }

  @JvmStatic fun d(tag: String, msg: String): Int { write("DEBUG", tag, msg, null, false); return 0 }
  @JvmStatic fun d(tag: String, msg: String, tr: Throwable?): Int { write("DEBUG", tag, msg, tr, false); return 0 }
  @JvmStatic fun i(tag: String, msg: String): Int { write("INFO", tag, msg, null, false); return 0 }
  @JvmStatic fun i(tag: String, msg: String, tr: Throwable?): Int { write("INFO", tag, msg, tr, false); return 0 }
  @JvmStatic fun w(tag: String, msg: String): Int { write("WARN", tag, msg, null, true); return 0 }
  @JvmStatic fun w(tag: String, tr: Throwable?): Int { write("WARN", tag, tr?.toString() ?: "", tr, true); return 0 }
  @JvmStatic fun w(tag: String, msg: String, tr: Throwable?): Int { write("WARN", tag, msg, tr, true); return 0 }
  @JvmStatic fun e(tag: String, msg: String): Int { write("ERROR", tag, msg, null, true); return 0 }
  @JvmStatic fun e(tag: String, msg: String, tr: Throwable?): Int { write("ERROR", tag, msg, tr, true); return 0 }
  @JvmStatic fun v(tag: String, msg: String): Int = d(tag, msg)
  @JvmStatic fun v(tag: String, msg: String, tr: Throwable?): Int = d(tag, msg, tr)
  @JvmStatic fun isLoggable(tag: String, level: Int): Boolean = true
}
