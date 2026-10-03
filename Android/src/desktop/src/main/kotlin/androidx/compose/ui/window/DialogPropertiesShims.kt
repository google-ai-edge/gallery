package androidx.compose.ui.window

fun DialogProperties(
  dismissOnBackPress: Boolean = true,
  dismissOnClickOutside: Boolean = true,
  usePlatformDefaultWidth: Boolean = true,
  decorFitsSystemWindows: Boolean = false,
): DialogProperties = DialogProperties(
  dismissOnBackPress = dismissOnBackPress,
  dismissOnClickOutside = dismissOnClickOutside,
  usePlatformDefaultWidth = usePlatformDefaultWidth,
)
