package androidx.compose.ui.platform

import android.app.Activity
import android.view.View
import androidx.compose.runtime.compositionLocalOf

val LocalView = compositionLocalOf { View(Activity()) }
