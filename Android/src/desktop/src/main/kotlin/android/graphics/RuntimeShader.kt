package android.graphics

open class Shader

class RuntimeShader(shaderString: String) : Shader() {
  fun setFloatUniform(name: String, value: Float) {}
  fun setFloatUniform(name: String, v1: Float, v2: Float) {}
  fun setFloatUniform(name: String, v1: Float, v2: Float, v3: Float, v4: Float) {}
  fun setFloatUniform(name: String, v1: Float, v2: Float, v3: Float, v4: Float, v5: Float) {}
}
