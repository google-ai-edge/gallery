package android.content

import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection

class ClipData(val text: CharSequence) {
  class Item(val text: CharSequence?)

  val itemCount: Int get() = 1
  fun getItemAt(index: Int): Item = Item(text)

  companion object {
    @JvmStatic
    fun newPlainText(label: CharSequence?, text: CharSequence?): ClipData {
      return ClipData(text ?: "")
    }

    @JvmStatic
    fun newRawUri(label: CharSequence?, uri: android.net.Uri?): ClipData {
      return ClipData(uri?.toString() ?: "")
    }

    @JvmStatic
    fun newUri(resolver: Any?, label: CharSequence?, uri: android.net.Uri?): ClipData {
      return ClipData(uri?.toString() ?: "")
    }
  }
}

class ClipboardManager {
  fun setPrimaryClip(clip: ClipData) {
    runCatching {
      val selection = StringSelection(clip.text.toString())
      Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, selection)
    }
  }

  fun getPrimaryClip(): ClipData? {
    return runCatching {
      val text = Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as? String
      text?.let { ClipData(it) }
    }.getOrNull()
  }

  val hasPrimaryClip: Boolean
    get() = runCatching {
      Toolkit.getDefaultToolkit().systemClipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)
    }.getOrDefault(false)
}
