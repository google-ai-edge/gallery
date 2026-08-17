/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.ui.translation;

import androidx.annotation.Keep;
import kotlin.jvm.functions.Function1;

@Keep
final class SherpaNativePcmCallback implements Function1<float[], Integer> {
  interface Handler {
    int onSamples(float[] samples);
  }

  private final Handler handler;

  SherpaNativePcmCallback(Handler handler) {
    this.handler = handler;
  }

  @Keep
  @Override
  public Integer invoke(float[] samples) {
    return handler.onSamples(samples);
  }
}
