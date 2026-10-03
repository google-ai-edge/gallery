package androidx.datastore

import android.content.Context
import java.io.File

fun Context.dataStoreFile(fileName: String): File =
  File(filesDir, "datastore/$fileName").also { it.parentFile?.mkdirs() }
