package androidx.activity.compose

import android.app.Activity
import androidx.compose.runtime.compositionLocalOf

val LocalActivity = compositionLocalOf<Activity?> { Activity() }
