package androidx.compose.ui.graphics

fun ShaderBrush(shader: android.graphics.Shader): Brush = SolidColor(Color.Transparent)
fun ShaderBrush(shader: android.graphics.RuntimeShader): Brush = SolidColor(Color.Transparent)
