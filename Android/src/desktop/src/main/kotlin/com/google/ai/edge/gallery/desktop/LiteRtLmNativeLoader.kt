package com.google.ai.edge.gallery.desktop

import android.util.Log
import java.io.File

/**
 * Loads the LiteRT-LM JNI library (`litertlm_jni.dll`) that the Windows package ships as a Compose
 * app resource (`<install>/app/resources/litertlm_jni.dll`).
 *
 * LiteRT-LM's own `NativeLibraryLoader` only looks on `java.library.path` and inside its jar; the
 * packaged app has neither (the jar is stripped of its ~380 MB of per-OS natives). Loading the DLL
 * here, from the same class loader as the LiteRT-LM classes, makes `NativeLibraryLoader` see it as
 * already loaded.
 */
object LiteRtLmNativeLoader {
  private const val TAG = "AGLiteRtLmNativeLoader"
  private const val DLL_NAME = "litertlm_jni.dll"

  @Volatile private var loadedFrom: File? = null

  /** Absolute path the DLL was loaded from, or null if it was not loaded by this class. */
  val loadedPath: String? get() = loadedFrom?.absolutePath

  @Synchronized
  fun load() {
    if (loadedFrom != null) return
    val candidates = buildList {
      System.getProperty("compose.application.resources.dir")?.let { add(File(it, DLL_NAME)) }
      // Next to the launcher / in the app dir (fallbacks for unusual layouts).
      System.getProperty("jpackage.app-path")?.let { File(it).parentFile }?.let {
        add(File(it, "app/resources/$DLL_NAME"))
        add(File(it, DLL_NAME))
      }
    }
    for (file in candidates) {
      if (!file.isFile) continue
      try {
        System.load(file.absolutePath)
        loadedFrom = file
        Log.i(TAG, "Loaded LiteRT-LM native library: ${file.absolutePath}")
        return
      } catch (t: Throwable) {
        Log.e(TAG, "Failed to load ${file.absolutePath}", t)
      }
    }
    // Not fatal here: LiteRT-LM will still try java.library.path / its jar, and if that also
    // fails, model initialization reports a proper error instead of hanging.
    Log.w(TAG, "$DLL_NAME not found in ${candidates.map { it.absolutePath }}")
  }
}
