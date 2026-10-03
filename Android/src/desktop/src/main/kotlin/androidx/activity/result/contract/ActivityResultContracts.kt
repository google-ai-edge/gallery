package androidx.activity.result.contract

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.result.ActivityResult
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.JFileChooser
import javax.swing.SwingUtilities

abstract class ActivityResultContract<I, O> {
  abstract fun handle(input: I, callback: (O) -> Unit)
}

object ActivityResultContracts {
  class StartActivityForResult : ActivityResultContract<Intent, ActivityResult>() {
    override fun handle(input: Intent, callback: (ActivityResult) -> Unit) {
      if (input.getBooleanExtra("is_hf_auth", false)) {
        SwingUtilities.invokeLater {
          try {
            java.awt.Desktop.getDesktop().browse(java.net.URI("https://huggingface.co/settings/tokens"))
          } catch (e: Exception) {}
          val token = javax.swing.JOptionPane.showInputDialog(
            null,
            "Please generate a Fine-grained or Read token at https://huggingface.co/settings/tokens and paste it here:",
            "Hugging Face Authentication",
            javax.swing.JOptionPane.PLAIN_MESSAGE
          )
          if (!token.isNullOrEmpty()) {
            val resultIntent = Intent().apply { putExtra("hf_desktop_token", token) }
            callback(ActivityResult(Activity.RESULT_OK, resultIntent))
          } else {
            callback(ActivityResult(Activity.RESULT_CANCELED, null))
          }
        }
        return
      }
      SwingUtilities.invokeLater {
        val dialog = FileDialog(null as Frame?, "Select Model File", FileDialog.LOAD)
        dialog.isVisible = true
        val file = dialog.file
        val dir = dialog.directory
        if (file != null && dir != null) {
          val selectedFile = File(dir, file)
          val uri = Uri.fromFile(selectedFile)
          val resultIntent = Intent().apply {
            data = uri
          }
          callback(ActivityResult(Activity.RESULT_OK, resultIntent))
        } else {
          callback(ActivityResult(Activity.RESULT_CANCELED, null))
        }
      }
    }
  }

  class RequestPermission : ActivityResultContract<String, Boolean>() {
    override fun handle(input: String, callback: (Boolean) -> Unit) {
      callback(true)
    }
  }

  class RequestMultiplePermissions : ActivityResultContract<Array<String>, Map<String, Boolean>>() {
    override fun handle(input: Array<String>, callback: (Map<String, Boolean>) -> Unit) {
      callback(input.associateWith { true })
    }
  }

  class OpenDocumentTree : ActivityResultContract<Uri?, Uri?>() {
    override fun handle(input: Uri?, callback: (Uri?) -> Unit) {
      SwingUtilities.invokeLater {
        val chooser = JFileChooser()
        chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        chooser.dialogTitle = "Select Folder"
        val ret = chooser.showOpenDialog(null)
        if (ret == JFileChooser.APPROVE_OPTION && chooser.selectedFile != null) {
          callback(Uri.fromFile(chooser.selectedFile))
        } else {
          callback(null)
        }
      }
    }
  }

  class GetContent : ActivityResultContract<String, Uri?>() {
    override fun handle(input: String, callback: (Uri?) -> Unit) {
      SwingUtilities.invokeLater {
        val dialog = FileDialog(null as Frame?, "Select File", FileDialog.LOAD)
        dialog.isVisible = true
        val file = dialog.file
        val dir = dialog.directory
        if (file != null && dir != null) {
          callback(Uri.fromFile(File(dir, file)))
        } else {
          callback(null)
        }
      }
    }
  }

  open class PickVisualMedia : ActivityResultContract<Any?, Uri?>() {
    object ImageOnly
    object ImageAndVideo
    object VideoOnly
    class SingleMimeType(val mimeType: String)

    override fun handle(input: Any?, callback: (Uri?) -> Unit) {
      SwingUtilities.invokeLater {
        val dialog = FileDialog(null as Frame?, "Select Image", FileDialog.LOAD)
        dialog.setFilenameFilter { _, name ->
          val lower = name.lowercase()
          lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".webp")
        }
        dialog.isVisible = true
        val file = dialog.file
        val dir = dialog.directory
        if (file != null && dir != null) {
          callback(Uri.fromFile(File(dir, file)))
        } else {
          callback(null)
        }
      }
    }
  }

  class PickMultipleVisualMedia : ActivityResultContract<Any?, List<Uri>>() {
    override fun handle(input: Any?, callback: (List<Uri>) -> Unit) {
      SwingUtilities.invokeLater {
        val dialog = FileDialog(null as Frame?, "Select Image(s)", FileDialog.LOAD)
        dialog.isMultipleMode = true
        dialog.setFilenameFilter { _, name ->
          val lower = name.lowercase()
          lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".webp")
        }
        dialog.isVisible = true
        val files = dialog.files
        if (files != null && files.isNotEmpty()) {
          callback(files.map { Uri.fromFile(it) })
        } else {
          callback(emptyList())
        }
      }
    }
  }

  class TakePicture : ActivityResultContract<Uri, Boolean>() {
    override fun handle(input: Uri, callback: (Boolean) -> Unit) {
      callback(false)
    }
  }
}
