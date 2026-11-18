/*
 * Copyright 2026 Google LLC
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

package com.google.android.datatransport.cct;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.android.datatransport.Encoding;
import com.google.android.datatransport.runtime.EncodedDestination;
import java.util.Collections;
import java.util.Set;

/** Clearcut destination compatible with both 1p firelog1p and 3p transport_runtime callers. */
@SuppressWarnings({"IdentifierName", "AbbreviationAsWordInName"})
public final class CCTDestination implements EncodedDestination {
  public static final CCTDestination INSTANCE = new CCTDestination();
  public static final CCTDestination LEGACY_INSTANCE = INSTANCE;

  private CCTDestination() {}

  @NonNull
  @Override
  public String getName() {
    return "cct";
  }

  @Nullable
  @Override
  public byte[] getExtras() {
    return null;
  }

  @Override
  public Set<Encoding> getSupportedEncodings() {
    return Collections.singleton(Encoding.of("proto"));
  }
}
