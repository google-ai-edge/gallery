package android.content

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream

open class Context {
  val packageName: String = "com.google.ai.edge.gallery"

  open val applicationContext: Context get() = this

  val appDataDir: File by lazy {
    val localAppData = System.getenv("LOCALAPPDATA")
      ?: (System.getProperty("user.home") + File.separator + "AppData" + File.separator + "Local")
    File(localAppData, "AIEdgeGallery").apply { mkdirs() }
  }

  val filesDir: File by lazy {
    File(appDataDir, "files").apply { mkdirs() }
  }

  val cacheDir: File by lazy {
    File(appDataDir, "cache").apply { mkdirs() }
  }

  fun getExternalFilesDir(type: String?): File {
    val dir = if (type == null) filesDir else File(filesDir, type)
    dir.mkdirs()
    return dir
  }

  open val assets: AssetManager = AssetManager()

  open val contentResolver: ContentResolver = ContentResolver()

  open val applicationInfo: android.content.pm.ApplicationInfo = android.content.pm.ApplicationInfo()

  open fun startActivity(intent: Intent) {
    val uri = intent.data ?: return
    try {
      java.awt.Desktop.getDesktop().browse(java.net.URI(uri.toString()))
    } catch (e: Exception) {
      e.printStackTrace()
    }
  }

  open fun getSystemService(name: String): Any? = when (name) {
    CLIPBOARD_SERVICE -> ClipboardManager()
    UI_MODE_SERVICE -> android.app.UiModeManager()
    CAMERA_SERVICE -> android.hardware.camera2.CameraManager()
    SENSOR_SERVICE -> android.hardware.SensorManager()
    ACTIVITY_SERVICE, "activity" -> android.app.ActivityManager()
    ALARM_SERVICE, "alarm" -> android.app.AlarmManager()
    NOTIFICATION_SERVICE, "notification" -> android.app.NotificationManager()
    else -> null
  }

  open fun openFileInput(name: String): FileInputStream = FileInputStream(File(filesDir, name))

  open fun openFileOutput(name: String, mode: Int): FileOutputStream = FileOutputStream(File(filesDir, name))

  open val resources: android.content.res.Resources = android.content.res.Resources()

  open fun getString(resId: Int): String =
    com.google.ai.edge.gallery.R.getString(resId)

  open fun getString(resId: Int, vararg formatArgs: Any): String =
    com.google.ai.edge.gallery.R.getString(resId, *formatArgs)

  class AssetManager {
    private fun loader(): ClassLoader =
      Thread.currentThread().contextClassLoader ?: Context::class.java.classLoader

    /** All bundled asset paths (relative to `assets/`), generated at build time. */
    private val index: List<String> by lazy {
      loader().getResourceAsStream("assets/asset_index.txt")?.bufferedReader()?.use { r ->
        r.readLines().filter { it.isNotBlank() }
      } ?: emptyList()
    }

    fun open(fileName: String): InputStream {
      val cleanPath = fileName.removePrefix("/")
      return loader().getResourceAsStream("assets/$cleanPath")
        ?: throw java.io.FileNotFoundException("Asset not found: $fileName")
    }

    /** Mirrors Android's AssetManager.list(): direct children (files and dirs) of [path]. */
    fun list(path: String): Array<String> {
      val prefix = path.trim('/').let { if (it.isEmpty()) "" else "$it/" }
      return index
        .filter { it.startsWith(prefix) }
        .map { it.removePrefix(prefix).substringBefore('/') }
        .distinct()
        .toTypedArray()
    }
  }

  companion object {
    const val CLIPBOARD_SERVICE: String = "clipboard"
    const val UI_MODE_SERVICE: String = "uimode"
    const val CAMERA_SERVICE: String = "camera"
    const val SENSOR_SERVICE: String = "sensor"
    const val ACTIVITY_SERVICE: String = "activity"
    const val ALARM_SERVICE: String = "alarm"
    const val NOTIFICATION_SERVICE: String = "notification"
    val INSTANCE: Context = Context()
  }
}
