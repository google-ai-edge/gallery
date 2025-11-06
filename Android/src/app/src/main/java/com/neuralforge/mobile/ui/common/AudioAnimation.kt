/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.neuralforge.mobile.ui.common

import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import kotlin.math.pow
import kotlin.random.Random

private const val SHADER =
  """
// The size of the render area.
uniform float2 iResolution;
// The color of the background to render the wave on.
uniform vec4 bgColor;
// Current timestamp in seconds.
uniform float iTime;
// The amplitude of the sound to be visualized.
// From 0 to 1.
uniform float amplitude;
// The extra offset for 1d perlin noise.
uniform float pOffset;

// Creates a gradient that blends four different colors based on a uv coordinate and animated
// over time.
vec3 mix4(vec3 color1, vec3 color2, vec3 color3, vec3 color4, vec2 uv){
  float sinTime1 = sin(iTime / 1.6);
  float sinTime2 = sin(iTime / 1.8);
  return mix(
    mix(color1, color2, smoothstep(0.0 + sinTime1 * 0.1, 0.24 + sinTime1 * 0.1, uv.y)),
    mix(color3, color4, smoothstep(-0.16 - sinTime2 * 0.1, 0.24 - sinTime2 * 0.1, uv.y)),
    smoothstep(0.0, 0.7 + sinTime1 * 0.1, uv.x));
}

float hash(float i) {
	float h = i * 127.1;
	float p = -1. + 2. * fract(sin(h) * 43758.1453123);
  return p;
}

float perlin_noise_1d(float d) {
  float i = floor(d);
  float f = d - i;

  float y = f*f*f* (6. * f*f - 15. * f + 10.);

  float slope1 = hash(i);
  float slope2 = hash(i + 1.0);
  float v1 = f;
  float v2 = f - 1.0;

  float r = mix(slope1 * v1, slope2 * v2, y);
  r = r * 0.5 + 0.5;
  return r;
}

half4 main(float2 fragCoord) {
  float2 uv = fragCoord/iResolution.xy;
  uv.y = 1.0 - uv.y;

  // Add a wavy distortion to the y-coordinate of the uv.
  //
  // Control the amplitude of the wave
  float wave_strength = 0.036;
  // Control the speed of the wave
  float wave_speed = 1.2;
  // Control the frequency of the wave
  float wave_frequency = 4.0;

  // Idle.
  if (amplitude == 0.) {
    uv.y += sin(uv.x * wave_frequency + -iTime * wave_speed) * wave_strength;
  }
  // Visualizing amplitude by sampling the 1d perlin noise at the given offset.
  else {
    uv.y -= perlin_noise_1d(pOffset + uv.x * 3.) * amplitude / 2.0;
  }

  vec3 col = mix4(
    vec3(0.992, 0.875, 0.522),  // yellow
    vec3(0.627, 0.816, 0.686),  // green
    vec3(0.886, 0.372, 0.341),  // red
    vec3(0.522, 0.694, 0.973),  // blue
    uv);

  // Define the fade parameters
  float fade_start = 0.24;
  float fade_end = 0.34;

  // Calculate the blend factor using smoothstep for a smooth transition
  float fade_factor = smoothstep(fade_start, fade_end, uv.y);

  // Blend the base color with background color using the fade factor
  vec4 final_color = mix(vec4(col, 1.0), bgColor, fade_factor);

  return vec4(half3(final_color.xyz) * (1 + amplitude * 0.2), final_color.a);
}
"""

/**
 * This composable function displays a shader-based audio animation.
 *
 * It uses a `RuntimeShader` to create a dynamically animated visual effect that responds to an
 * audio amplitude. The shader renders a gradient with a wavy distortion. It moves slowly when
 * waiting for recording to start (amplitude is 0), and reacts to amplitude changes by rendering
 * random "bumps" from 1d perlin noise.
 */
@Composable
fun AudioAnimation(bgColor: Color, amplitude: Int, modifier: Modifier = Modifier) {
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    val shader = remember { RuntimeShader(SHADER) }
    val shaderBrush = remember { ShaderBrush(shader) }
    var iTime by remember { mutableFloatStateOf(0f) }
    var curPOffset by remember { mutableFloatStateOf(0f) }
    var prevNormalizedAmplitude by remember { mutableDoubleStateOf(0.0) }
    // Use pow(x, 0.5) to make low amplitude levels more significant.
    val normalizedAmplitude = (amplitude / 32767.0).pow(0.5)
    var animatedAmplitude by remember { mutableFloatStateOf(normalizedAmplitude.toFloat()) }

    // Animate the amplitude value whenever amplitude changes.
    // This will drive the animation from the current value to the new target value.
    LaunchedEffect(amplitude) {
      val animatable = Animatable(initialValue = animatedAmplitude)
      animatable.animateTo(
        targetValue = normalizedAmplitude.toFloat(),
        animationSpec = tween(durationMillis = 100),
      ) {
        animatedAmplitude = this.value
      }
    }

    // Updates the iTime uniform for the shader.
    LaunchedEffect(Unit) {
      while (true) {
        withFrameMillis { frameTimeMs -> iTime = frameTimeMs / 1000f }
      }
    }

    // Shader rending.
    Canvas(modifier = modifier.fillMaxSize()) {
      // Add a random offset to the Perlin noise whenever the audio amplitude drops from a high
      // level (0.2 or greater) to a low level (less than 0.2). This makes the noise-driven visual
      // effect appear to "jump" or reset to a new, random state when the audio becomes quiet,
      // preventing the visual from settling into a repetitive or static pattern.
      if (normalizedAmplitude < 0.2 && prevNormalizedAmplitude >= 0.2) {
        curPOffset = Random.nextFloat() * 1000f
      }
      prevNormalizedAmplitude = normalizedAmplitude

      shader.setFloatUniform("iTime", iTime)
      shader.setFloatUniform("iResolution", size.width, size.height)
      shader.setFloatUniform("bgColor", bgColor.red, bgColor.green, bgColor.blue, bgColor.alpha)
      shader.setFloatUniform("amplitude", animatedAmplitude)
      shader.setFloatUniform("pOffset", curPOffset)

      drawRect(brush = shaderBrush)
    }
  }
}
