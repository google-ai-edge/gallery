package com.google.ai.edge.gallery.ui.common

import androidx.compose.runtime.compositionLocalOf
import com.google.ai.edge.gallery.data.AppContainer

val LocalAppContainer = compositionLocalOf<AppContainer> { error("AppContainer not provided") }
