package androidx.compose.ui.text.font

import com.google.ai.edge.gallery.R

// Android Font(R.font.x) -> Compose Desktop font loaded from the classpath.
// The upstream res/font/*.ttf files are bundled under font/ by the desktop build.
fun Font(
  resId: Int,
  weight: FontWeight = FontWeight.Normal,
  style: FontStyle = FontStyle.Normal,
): Font =
  androidx.compose.ui.text.platform.Font(
    resource = "font/${R.getFontName(resId)}.ttf",
    weight = weight,
    style = style,
  )
