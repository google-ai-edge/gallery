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

package com.google.ai.edge.gallery.services.tts

import java.util.Locale

/**
 * Translated sample phrases for `"Welcome to AI Edge Gallery"` spoken when previewing a TTS voice
 * profile.
 *
 * Unlike UI strings resolved against the device's system locale (`context.getString`), voice
 * preview utterances must match the **target voice's spoken language** (e.g., French for PocketTTS
 * `"estelle"`, Spanish for `"lola"`, German for `"juergen"`, or the user's selected TTS language
 * for multi-language voices) regardless of the phone's UI language or which Play Store dynamic
 * language splits are installed.
 */
object TtsVoicePreviewPhrases {

  /** Default English preview phrase. */
  const val DEFAULT_PREVIEW_TEXT = "Welcome to AI Edge Gallery"

  /**
   * Translated preview phrases keyed by lowercase ISO 639-1 primary language subtag.
   *
   * For non-Latin-script languages (`ar`, `hi`, `ja`, `ko`, `ru`, `zh`), `"AI Edge Gallery"` is
   * transliterated into the target script so monolingual neural TTS phonemizers pronounce the
   * product name naturally instead of dropping Latin characters.
   */
  val phrasesByLanguageSubtag: Map<String, String> =
    mapOf(
      "ar" to "مرحبًا بك في إيه آي إيدج غاليري",
      "da" to "Velkommen til AI Edge Gallery",
      "de" to "Willkommen bei AI Edge Gallery",
      "en" to DEFAULT_PREVIEW_TEXT,
      "es" to "Te damos la bienvenida a AI Edge Gallery",
      "fr" to "Bienvenue dans AI Edge Gallery",
      "hi" to "एआई एज गैलरी में आपका स्वागत है",
      "id" to "Selamat datang di AI Edge Gallery",
      "it" to "Ti diamo il benvenuto in AI Edge Gallery",
      "ja" to "エーアイ エッジ ギャラリーへようこそ",
      "ko" to "에이아이 엣지 갤러리에 오신 것을 환영합니다",
      "nb" to "Velkommen til AI Edge Gallery",
      "nl" to "Welkom bij AI Edge Gallery",
      "no" to "Velkommen til AI Edge Gallery",
      "pl" to "Witamy w AI Edge Gallery",
      "pt" to "Boas-vindas ao AI Edge Gallery",
      "ru" to "Добро пожаловать в Эй Ай Эдж Гэллери",
      "sv" to "Välkommen till AI Edge Gallery",
      "tr" to "AI Edge Gallery'ye hoş geldiniz",
      "zh" to "欢迎使用 A I Edge Gallery",
    )

  /**
   * Returns the translated `"Welcome to AI Edge Gallery"` sample phrase for [languageCode] (a
   * BCP-47 tag such as `"fr-FR"` or ISO 639-1 code such as `"fr"`), falling back to [fallbackText]
   * when [languageCode] is null, blank, or not in [phrasesByLanguageSubtag].
   */
  fun previewText(languageCode: String?, fallbackText: String = DEFAULT_PREVIEW_TEXT): String {
    val trimmed = languageCode?.trim().orEmpty()
    if (trimmed.isEmpty()) {
      return fallbackText
    }
    val subtag = trimmed.replace('_', '-').substringBefore('-').lowercase(Locale.ROOT)
    return phrasesByLanguageSubtag[subtag] ?: fallbackText
  }
}
