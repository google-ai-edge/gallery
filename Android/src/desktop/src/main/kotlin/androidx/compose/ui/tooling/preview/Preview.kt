package androidx.compose.ui.tooling.preview

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
annotation class Preview(
  val name: String = "",
  val group: String = "",
  val showBackground: Boolean = false,
  val backgroundColor: Long = 0,
  val widthDp: Int = -1,
  val heightDp: Int = -1
)
