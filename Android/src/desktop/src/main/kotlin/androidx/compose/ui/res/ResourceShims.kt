package androidx.compose.ui.res

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R
import java.util.concurrent.ConcurrentHashMap
import org.xml.sax.InputSource

/**
 * Desktop implementation of Android resource lookups used by the shared Compose UI.
 *
 * Strings, plurals and dimensions come from the generated [R] (built from upstream `res/values`).
 * Drawables are the upstream Android vector XML files, bundled on the classpath under `drawable/`
 * and parsed with Compose Desktop's Android-vector-drawable parser.
 */
@Composable
fun stringResource(id: Int): String = R.getString(id)

@Composable
fun stringResource(id: Int, vararg formatArgs: Any): String = R.getString(id, *formatArgs)

@Composable
fun pluralStringResource(id: Int, count: Int): String = R.getQuantityString(id, count)

@Composable
fun pluralStringResource(id: Int, count: Int, vararg formatArgs: Any): String =
  R.getQuantityString(id, count, *formatArgs)

@Composable
fun dimensionResource(id: Int): Dp = R.getDimensionDp(id).dp

private val vectorCache = ConcurrentHashMap<Int, ImageVector>()

private val fallbackVector: ImageVector by lazy {
  ImageVector.Builder(defaultWidth = 1.dp, defaultHeight = 1.dp, viewportWidth = 1f, viewportHeight = 1f)
    .build()
}

/** Loads an upstream Android `<vector>` drawable (also unwraps `<animated-vector>`). */
internal fun loadDrawableVector(id: Int): ImageVector =
  vectorCache.getOrPut(id) {
    val name = R.getDrawableName(id)
    val path = "drawable/$name.xml"
    try {
      val bytes =
        (Thread.currentThread().contextClassLoader?.getResourceAsStream(path)
            ?: R::class.java.classLoader.getResourceAsStream(path))
          ?.use { it.readBytes() } ?: error("Missing drawable resource $path")
      var xml = String(bytes, Charsets.UTF_8)
      if (xml.contains("<animated-vector")) {
        // Use the static <vector> embedded in the animated drawable.
        val start = xml.indexOf("<vector")
        val end = xml.indexOf("</vector>")
        if (start >= 0 && end > start) {
          xml =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
              xml.substring(start, end + "</vector>".length).replaceFirst(
                "<vector",
                "<vector xmlns:android=\"http://schemas.android.com/apk/res/android\" " +
                  "xmlns:aapt=\"http://schemas.android.com/aapt\"",
              )
        }
      }
      loadXmlImageVector(InputSource(xml.reader()), Density(1f))
    } catch (e: Exception) {
      System.err.println("[resources] Failed to load drawable '$name': ${e.message}")
      fallbackVector
    }
  }

@Composable
fun painterResource(id: Int): Painter {
  val vector = remember(id) { loadDrawableVector(id) }
  return if (vector === fallbackVector) ColorPainter(Color.Transparent) else rememberVectorPainter(vector)
}

fun ImageVector.Companion.vectorResource(id: Int): ImageVector = loadDrawableVector(id)

@Composable
fun vectorResource(id: Int): ImageVector = remember(id) { loadDrawableVector(id) }
