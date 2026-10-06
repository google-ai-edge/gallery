package androidx.compose.ui.platform

import android.content.ClipData
import androidx.compose.runtime.compositionLocalOf
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection

class ClipEntry(val clipData: ClipData? = null) {
  constructor(text: String) : this(ClipData(text))
  val text: String = clipData?.text?.toString() ?: ""
}

fun String.toClipEntry(): ClipEntry = ClipEntry(this)
fun ClipData.toClipEntry(): ClipEntry = ClipEntry(this)
fun CharSequence.toClipEntry(): ClipEntry = ClipEntry(this.toString())

interface Clipboard {
  suspend fun setClipEntry(clipEntry: ClipEntry?)
  suspend fun getClipEntry(): ClipEntry?
}

val LocalClipboard = compositionLocalOf<Clipboard> {
  object : Clipboard {
    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
      runCatching {
        val str = clipEntry?.text ?: ""
        val selection = StringSelection(str)
        Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, selection)
      }
    }

    override suspend fun getClipEntry(): ClipEntry? {
      return runCatching {
        val str = Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as? String
        str?.let { ClipEntry(it) }
      }.getOrNull()
    }
  }
}
