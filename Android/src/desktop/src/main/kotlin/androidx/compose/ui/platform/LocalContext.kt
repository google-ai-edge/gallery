package androidx.compose.ui.platform

import android.content.Context
import androidx.compose.runtime.compositionLocalOf

val LocalContext = compositionLocalOf { Context.INSTANCE }
