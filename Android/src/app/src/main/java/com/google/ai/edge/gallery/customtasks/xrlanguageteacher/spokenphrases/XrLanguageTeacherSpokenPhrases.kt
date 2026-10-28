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

package com.google.ai.edge.gallery.customtasks.xrlanguageteacher.spokenphrases

import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.CharacterPersona
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.resolveEngineLocaleTagForLanguage
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.toIsoLanguageCode
import com.google.ai.edge.gallery.proto.Language

/**
 * Fixed lines the teacher speaks, rather than the model generating them, in one native language.
 *
 * These follow the student's native-language setting rather than the system locale, so they are not
 * string resources.
 *
 * @property greeting Shown and spoken when the student opens the camera view.
 * @property fillers Short phrases spoken while the model is still thinking.
 * @property personaPreviews The line each persona speaks when the student previews its voice.
 */
internal data class SpokenPhraseSet(
  val greeting: String,
  val fillers: List<String>,
  val personaPreviews: Map<CharacterPersona, String>,
)

/**
 * A [SpokenPhraseSet] and the BCP-47 [languageTag] to speak it with.
 *
 * @property phrases The phrases to show and speak.
 * @property languageTag The locale the TTS engine should use for [phrases].
 */
internal data class ResolvedSpokenPhrases(val phrases: SpokenPhraseSet, val languageTag: String)

/**
 * Spoken phrases for every native language the teacher can speak.
 *
 * ### Why spoken TTS phrases live in Kotlin rather than `res/values-<locale>/strings.xml`
 * 1. **Decoupled from UI locale (`context.getString`):** Android string resources resolve against
 *    the device/app UI locale, whereas spoken teacher greetings, thinking fillers, and persona
 *    voice previews must match the student's selected **native language** (`mainLanguage`) and the
 *    active TTS engine's supported locales—even when the phone UI is set to a different language.
 * 2. **Play Store language split delivery:** Even if `Configuration.setLocale(...)` and
 *    `Context.createConfigurationContext(...)` were used, Android App Bundle dynamic language split
 *    delivery only installs `values-<lang>` resource splits for the user's system locales. Lookup
 *    for any other `mainLanguage` picked inside the app would silently fall back to the base
 *    `values/strings.xml` (English) on production devices.
 * 3. **Deterministic offline availability:** Keeping all 21 spoken phrase sets in a self-contained
 *    Kotlin lookup table guarantees that every language any on-device TTS engine (ML Kit, Kokoro,
 *    or System TTS) can speak is always available offline.
 */
internal object XrLanguageTeacherSpokenPhrases {

  /** ISO 639-1 code of the language used when a native language has no phrase set. */
  private const val FALLBACK_LANGUAGE_CODE = "en"

  /** Locale used to speak the fallback phrase set. */
  private const val FALLBACK_LANGUAGE_TAG = "en-US"

  /**
   * Spoken phrases keyed by ISO 639-1 language code. Covers every language that any TTS engine the
   * app uses can speak, plus every language in the app's language list.
   */
  val phrasesByLanguageCode: Map<String, SpokenPhraseSet> =
    mapOf(
      "en" to
        SpokenPhraseSet(
          greeting = "Hi there! What are you looking to learn about today?",
          fillers =
            listOf(
              "Let me see…",
              "Hmm, let me take a look…",
              "Let's check this out…",
              "Looking at this…",
              "One moment, let me see…",
              "Hmm, interesting…",
              "Let's see what we have here…",
            ),
          personaPreviews =
            mapOf(
              CharacterPersona.BUDDY to "Hi! I'm Buddy! Let's learn together!",
              CharacterPersona.NERDY to
                "Hi, I'm Nerdy! Did you know every word has a secret history? Let's dig in!",
              CharacterPersona.SECRET_AGENT to
                "Agent on standby. Your mission today: learn a new language, quietly and efficiently.",
              CharacterPersona.WIZARD to
                "Greetings, young apprentice! Every word you learn is a new spell. Shall we begin?",
            ),
        ),
      "ar" to
        SpokenPhraseSet(
          greeting = "مرحبًا! ما الذي تود أن تتعلمه اليوم؟",
          fillers =
            listOf(
              "دعني أرى…",
              "همم، دعني ألقي نظرة…",
              "لنلقِ نظرة على هذا…",
              "بالنظر إلى هذا…",
              "لحظة واحدة، دعني أرى…",
              "همم، مثير للاهتمام…",
              "لنرَ ما لدينا هنا…",
            ),
          personaPreviews =
            mapOf(
              CharacterPersona.BUDDY to "مرحبًا! أنا بادي! لنتعلم معًا!",
              CharacterPersona.NERDY to
                "مرحبًا، أنا نيردي! هل تعلم أن لكل كلمة تاريخًا سريًا؟ هيا نكتشفه!",
              CharacterPersona.SECRET_AGENT to
                "العميل في وضع الاستعداد. مهمتك اليوم: تعلّم لغة جديدة، بهدوء وكفاءة.",
              CharacterPersona.WIZARD to
                "تحياتي أيها التلميذ الشاب! كل كلمة تتعلمها هي تعويذة جديدة. هل نبدأ؟",
            ),
        ),
      "da" to
        SpokenPhraseSet(
          greeting = "Hej! Hvad har du lyst til at lære om i dag?",
          fillers =
            listOf(
              "Lad mig se…",
              "Hmm, lad mig lige kigge…",
              "Lad os se på det her…",
              "Hvis vi kigger på det her…",
              "Et øjeblik, lad mig se…",
              "Hmm, spændende…",
              "Lad os se, hvad vi har her…",
            ),
          personaPreviews =
            mapOf(
              CharacterPersona.BUDDY to "Hej! Jeg er Buddy! Lad os lære sammen!",
              CharacterPersona.NERDY to
                "Hej, jeg er Nerdy! Vidste du, at hvert ord har en hemmelig historie? Lad os dykke ned i det!",
              CharacterPersona.SECRET_AGENT to
                "Agent i beredskab. Din mission i dag: lær et nyt sprog, diskret og effektivt.",
              CharacterPersona.WIZARD to
                "Vær hilset, unge lærling! Hvert ord, du lærer, er en ny trylleformular. Skal vi begynde?",
            ),
        ),
      "de" to
        SpokenPhraseSet(
          greeting = "Hallo! Worüber möchtest du heute etwas lernen?",
          fillers =
            listOf(
              "Mal sehen…",
              "Hmm, lass mich mal schauen…",
              "Schauen wir uns das mal an…",
              "Wenn ich mir das so ansehe…",
              "Einen Moment, mal sehen…",
              "Hmm, interessant…",
              "Mal sehen, was wir hier haben…",
            ),
          personaPreviews =
            mapOf(
              CharacterPersona.BUDDY to "Hi! Ich bin Buddy! Lass uns zusammen lernen!",
              CharacterPersona.NERDY to
                "Hi, ich bin Nerdy! Wusstest du, dass jedes Wort eine geheime Geschichte hat? Tauchen wir ein!",
              CharacterPersona.SECRET_AGENT to
                "Agent in Bereitschaft. Deine heutige Mission: eine neue Sprache lernen, unauffällig und effizient.",
              CharacterPersona.WIZARD to
                "Sei gegrüßt, junger Lehrling! Jedes Wort, das du lernst, ist ein neuer Zauberspruch. Wollen wir beginnen?",
            ),
        ),
      "es" to
        SpokenPhraseSet(
          greeting = "¡Hola! ¿Sobre qué te gustaría aprender hoy?",
          fillers =
            listOf(
              "A ver…",
              "Mmm, déjame echar un vistazo…",
              "Vamos a ver esto…",
              "Fíjate en esto…",
              "Un momento, a ver…",
              "Mmm, interesante…",
              "Veamos qué tenemos aquí…",
            ),
          personaPreviews =
            mapOf(
              CharacterPersona.BUDDY to "¡Hola! ¡Soy Buddy! ¡Aprendamos juntos!",
              CharacterPersona.NERDY to
                "¡Hola, soy Nerdy! ¿Sabías que cada palabra tiene una historia secreta? ¡Vamos a descubrirla!",
              CharacterPersona.SECRET_AGENT to
                "Agente a la espera. Tu misión de hoy: aprender un nuevo idioma, con discreción y eficacia.",
              CharacterPersona.WIZARD to
                "¡Saludos, joven aprendiz! Cada palabra que aprendes es un nuevo hechizo. ¿Empezamos?",
            ),
        ),
      "fr" to
        SpokenPhraseSet(
          greeting = "Bonjour ! Qu'aimerais-tu apprendre aujourd'hui ?",
          fillers =
            listOf(
              "Voyons voir…",
              "Hmm, laisse-moi jeter un œil…",
              "Regardons ça…",
              "Alors, qu'est-ce que c'est…",
              "Un instant, voyons…",
              "Hmm, intéressant…",
              "Voyons ce qu'on a ici…",
            ),
          personaPreviews =
            mapOf(
              CharacterPersona.BUDDY to "Salut ! Moi, c'est Buddy ! Apprenons ensemble !",
              CharacterPersona.NERDY to
                "Salut, moi c'est Nerdy ! Tu savais que chaque mot a une histoire secrète ? Allons la découvrir !",
              CharacterPersona.SECRET_AGENT to
                "Agent en position. Ta mission du jour : apprendre une nouvelle langue, discrètement et efficacement.",
              CharacterPersona.WIZARD to
                "Salutations, jeune apprenti ! Chaque mot que tu apprends est un nouveau sortilège. On commence ?",
            ),
        ),
      "hi" to
        SpokenPhraseSet(
          greeting = "नमस्ते! आज आप किस बारे में सीखना चाहेंगे?",
          fillers =
            listOf(
              "ज़रा देखूँ…",
              "हम्म, ज़रा देखते हैं…",
              "चलिए इसे देखते हैं…",
              "अच्छा, तो यह…",
              "एक पल, ज़रा देखूँ…",
              "हम्म, दिलचस्प…",
              "देखते हैं यहाँ क्या है…",
            ),
          personaPreviews =
            mapOf(
              CharacterPersona.BUDDY to "नमस्ते! मैं बडी हूँ! चलो साथ में सीखते हैं!",
              CharacterPersona.NERDY to
                "नमस्ते, मैं नर्डी हूँ! क्या आप जानते हैं कि हर शब्द का एक छिपा हुआ इतिहास होता है? चलिए पता लगाते हैं!",
              CharacterPersona.SECRET_AGENT to
                "एजेंट तैयार है। आज का मिशन: एक नई भाषा सीखना, चुपचाप और कुशलता से।",
              CharacterPersona.WIZARD to
                "स्वागत है, युवा शिष्य! आप जो भी शब्द सीखते हैं, वह एक नया जादुई मंत्र है। क्या हम शुरू करें?",
            ),
        ),
      "id" to
        SpokenPhraseSet(
          greeting = "Halo! Hari ini kamu ingin belajar tentang apa?",
          fillers =
            listOf(
              "Coba kulihat…",
              "Hmm, coba kulihat dulu…",
              "Ayo kita lihat ini…",
              "Kalau dilihat dari sini…",
              "Sebentar, coba kulihat…",
              "Hmm, menarik…",
              "Mari kita lihat ada apa di sini…",
            ),
          personaPreviews =
            mapOf(
              CharacterPersona.BUDDY to "Hai! Aku Buddy! Ayo belajar bersama!",
              CharacterPersona.NERDY to
                "Hai, aku Nerdy! Tahukah kamu setiap kata punya sejarah rahasia? Ayo kita gali!",
              CharacterPersona.SECRET_AGENT to
                "Agen siaga. Misimu hari ini: belajar bahasa baru, diam-diam dan efisien.",
              CharacterPersona.WIZARD to
                "Salam, murid muda! Setiap kata yang kamu pelajari adalah mantra baru. Siap untuk mulai?",
            ),
        ),
      "it" to
        SpokenPhraseSet(
          greeting = "Ciao! Cosa ti piacerebbe imparare oggi?",
          fillers =
            listOf(
              "Vediamo un po'…",
              "Mmm, fammi dare un'occhiata…",
              "Diamo un'occhiata a questo…",
              "Osserviamo bene…",
              "Un momento, vediamo…",
              "Mmm, interessante…",
              "Vediamo cosa abbiamo qui…",
            ),
          personaPreviews =
            mapOf(
              CharacterPersona.BUDDY to "Ciao! Sono Buddy! Impariamo insieme!",
              CharacterPersona.NERDY to
                "Ciao, sono Nerdy! Lo sapevi che ogni parola ha una storia segreta? Scopriamola insieme!",
              CharacterPersona.SECRET_AGENT to
                "Agente in posizione. La tua missione di oggi: imparare una nuova lingua, con discrezione ed efficienza.",
              CharacterPersona.WIZARD to
                "Salve, giovane apprendista! Ogni parola che impari è un nuovo incantesimo. Cominciamo?",
            ),
        ),
      "ja" to
        SpokenPhraseSet(
          greeting = "こんにちは！今日は何について学びたいですか？",
          fillers =
            listOf(
              "えーと…",
              "うーん、ちょっと見てみますね…",
              "これを見てみましょう…",
              "これを見ると…",
              "少し待ってくださいね、えーと…",
              "うーん、面白いですね…",
              "さて、何があるか見てみましょう…",
            ),
          personaPreviews =
            mapOf(
              CharacterPersona.BUDDY to "こんにちは！バディです！一緒に学びましょう！",
              CharacterPersona.NERDY to "こんにちは、ナーディです！どの言葉にも秘密の歴史があるって知っていましたか？さあ、掘り下げてみましょう！",
              CharacterPersona.SECRET_AGENT to "エージェント、待機中。本日の任務：静かに、効率よく、新しい言語を習得せよ。",
              CharacterPersona.WIZARD to "ようこそ、若き弟子よ！覚える言葉の一つ一つが新しい呪文じゃ。さあ、始めようか？",
            ),
        ),
      "ko" to
        SpokenPhraseSet(
          greeting = "안녕하세요! 오늘은 무엇을 배워 볼까요?",
          fillers =
            listOf(
              "어디 보자…",
              "음, 한번 볼게요…",
              "이거 한번 살펴볼까요…",
              "이걸 보니까…",
              "잠깐만요, 어디 보자…",
              "음, 흥미롭네요…",
              "여기 뭐가 있는지 볼까요…",
            ),
          personaPreviews =
            mapOf(
              CharacterPersona.BUDDY to "안녕하세요! 저는 버디예요! 같이 배워 봐요!",
              CharacterPersona.NERDY to "안녕하세요, 저는 너디예요! 모든 단어에는 숨겨진 역사가 있다는 거 알고 있었어요? 함께 파헤쳐 봐요!",
              CharacterPersona.SECRET_AGENT to "요원 대기 중. 오늘의 임무: 조용하고 효율적으로 새로운 언어를 배우는 것.",
              CharacterPersona.WIZARD to "반갑구나, 젊은 견습생이여! 배우는 단어 하나하나가 새로운 주문이란다. 시작해 볼까?",
            ),
        ),
      "nb" to
        SpokenPhraseSet(
          greeting = "Hei! Hva har du lyst til å lære om i dag?",
          fillers =
            listOf(
              "La meg se…",
              "Hmm, la meg ta en titt…",
              "La oss se på dette…",
              "Hvis vi ser på dette…",
              "Et øyeblikk, la meg se…",
              "Hmm, interessant…",
              "La oss se hva vi har her…",
            ),
          personaPreviews =
            mapOf(
              CharacterPersona.BUDDY to "Hei! Jeg er Buddy! La oss lære sammen!",
              CharacterPersona.NERDY to
                "Hei, jeg er Nerdy! Visste du at hvert ord har en hemmelig historie? La oss dykke ned i det!",
              CharacterPersona.SECRET_AGENT to
                "Agent i beredskap. Oppdraget ditt i dag: lær et nytt språk, diskret og effektivt.",
              CharacterPersona.WIZARD to
                "Vær hilset, unge lærling! Hvert ord du lærer, er en ny trylleformel. Skal vi begynne?",
            ),
        ),
      "nl" to
        SpokenPhraseSet(
          greeting = "Hallo! Waar wil je vandaag meer over leren?",
          fillers =
            listOf(
              "Even kijken…",
              "Hmm, laat me even kijken…",
              "Laten we dit eens bekijken…",
              "Als ik hiernaar kijk…",
              "Een momentje, even kijken…",
              "Hmm, interessant…",
              "Eens kijken wat we hier hebben…",
            ),
          personaPreviews =
            mapOf(
              CharacterPersona.BUDDY to "Hoi! Ik ben Buddy! Laten we samen leren!",
              CharacterPersona.NERDY to
                "Hoi, ik ben Nerdy! Wist je dat elk woord een geheime geschiedenis heeft? Laten we het uitzoeken!",
              CharacterPersona.SECRET_AGENT to
                "Agent stand-by. Je missie van vandaag: een nieuwe taal leren, onopvallend en efficiënt.",
              CharacterPersona.WIZARD to
                "Gegroet, jonge leerling! Elk woord dat je leert, is een nieuwe toverspreuk. Zullen we beginnen?",
            ),
        ),
      "pl" to
        SpokenPhraseSet(
          greeting = "Cześć! Czego chcesz się dziś nauczyć?",
          fillers =
            listOf(
              "Niech pomyślę…",
              "Hmm, zaraz zobaczę…",
              "Przyjrzyjmy się temu…",
              "Patrząc na to…",
              "Chwileczkę, zobaczmy…",
              "Hmm, ciekawe…",
              "Zobaczmy, co tu mamy…",
            ),
          personaPreviews =
            mapOf(
              CharacterPersona.BUDDY to "Cześć! Jestem Buddy! Uczmy się razem!",
              CharacterPersona.NERDY to
                "Cześć, jestem Nerdy! Czy wiesz, że każde słowo ma swoją ukrytą historię? Odkryjmy ją!",
              CharacterPersona.SECRET_AGENT to
                "Agent w gotowości. Twoja dzisiejsza misja: nauczyć się nowego języka, dyskretnie i skutecznie.",
              CharacterPersona.WIZARD to
                "Witaj, młody uczniu! Każde słowo, którego się uczysz, to nowe zaklęcie. Zaczynamy?",
            ),
        ),
      "pt" to
        SpokenPhraseSet(
          greeting = "Olá! O que gostaria de aprender hoje?",
          fillers =
            listOf(
              "Vamos ver…",
              "Hmm, vou dar uma olhada…",
              "Vamos ver isto…",
              "Olhando para isto…",
              "Um momento, vamos ver…",
              "Hmm, interessante…",
              "Vamos ver o que temos aqui…",
            ),
          personaPreviews =
            mapOf(
              CharacterPersona.BUDDY to "Olá! Eu sou o Buddy! Vamos aprender juntos!",
              CharacterPersona.NERDY to
                "Olá, eu sou o Nerdy! Sabia que cada palavra tem uma história secreta? Vamos descobri-la!",
              CharacterPersona.SECRET_AGENT to
                "Agente a postos. Sua missão de hoje: aprender um novo idioma, com discrição e eficiência.",
              CharacterPersona.WIZARD to
                "Saudações, jovem aprendiz! Cada palavra que aprende é um novo feitiço. Vamos começar?",
            ),
        ),
      "ru" to
        SpokenPhraseSet(
          greeting = "Привет! О чём ты хочешь узнать сегодня?",
          fillers =
            listOf(
              "Так, посмотрим…",
              "Хм, дай-ка взгляну…",
              "Давай посмотрим на это…",
              "Если посмотреть на это…",
              "Секунду, посмотрим…",
              "Хм, интересно…",
              "Посмотрим, что тут у нас…",
            ),
          personaPreviews =
            mapOf(
              CharacterPersona.BUDDY to "Привет! Я Бадди! Давай учиться вместе!",
              CharacterPersona.NERDY to
                "Привет, я Нёрди! А ты знал, что у каждого слова есть тайная история? Давай разберёмся!",
              CharacterPersona.SECRET_AGENT to
                "Агент на связи. Твоя миссия на сегодня: выучить новый язык — тихо и эффективно.",
              CharacterPersona.WIZARD to
                "Приветствую, юный ученик! Каждое выученное слово — это новое заклинание. Начнём?",
            ),
        ),
      "sv" to
        SpokenPhraseSet(
          greeting = "Hej! Vad vill du lära dig om i dag?",
          fillers =
            listOf(
              "Låt mig se…",
              "Hmm, låt mig ta en titt…",
              "Nu tittar vi på det här…",
              "Om man tittar på det här…",
              "Ett ögonblick, låt mig se…",
              "Hmm, intressant…",
              "Få se vad vi har här…",
            ),
          personaPreviews =
            mapOf(
              CharacterPersona.BUDDY to "Hej! Jag är Buddy! Kom, så lär vi oss tillsammans!",
              CharacterPersona.NERDY to
                "Hej, jag är Nerdy! Visste du att varje ord har en hemlig historia? Vi dyker ner i den!",
              CharacterPersona.SECRET_AGENT to
                "Agent i beredskap. Ditt uppdrag i dag: lär dig ett nytt språk, diskret och effektivt.",
              CharacterPersona.WIZARD to
                "Var hälsad, unge lärling! Varje ord du lär dig är en ny trollformel. Ska vi börja?",
            ),
        ),
      "th" to
        SpokenPhraseSet(
          greeting = "สวัสดี! วันนี้อยากเรียนรู้เรื่องอะไรดี?",
          fillers =
            listOf(
              "ขอดูหน่อยนะ…",
              "อืม ขอดูก่อนนะ…",
              "มาดูอันนี้กัน…",
              "ดูจากอันนี้นะ…",
              "รอสักครู่ ขอดูก่อนนะ…",
              "อืม น่าสนใจจัง…",
              "มาดูกันว่ามีอะไรบ้าง…",
            ),
          personaPreviews =
            mapOf(
              CharacterPersona.BUDDY to "สวัสดี! เราชื่อบัดดี้! มาเรียนด้วยกันนะ!",
              CharacterPersona.NERDY to
                "สวัสดี เราชื่อเนิร์ดดี้! รู้ไหมว่าทุกคำมีประวัติลับซ่อนอยู่? มาค้นหากันเถอะ!",
              CharacterPersona.SECRET_AGENT to
                "สายลับพร้อมปฏิบัติการ ภารกิจวันนี้: เรียนภาษาใหม่อย่างเงียบเชียบและมีประสิทธิภาพ",
              CharacterPersona.WIZARD to
                "ยินดีต้อนรับ ศิษย์น้อย! ทุกคำที่เจ้าเรียนรู้คือคาถาบทใหม่ เริ่มกันเลยไหม?",
            ),
        ),
      "tr" to
        SpokenPhraseSet(
          greeting = "Merhaba! Bugün ne hakkında bir şeyler öğrenmek istersin?",
          fillers =
            listOf(
              "Bir bakayım…",
              "Hmm, bir göz atayım…",
              "Şuna bir bakalım…",
              "Buna bakınca…",
              "Bir saniye, bakayım…",
              "Hmm, ilginç…",
              "Bakalım burada neler var…",
            ),
          personaPreviews =
            mapOf(
              CharacterPersona.BUDDY to "Merhaba! Ben Buddy! Hadi birlikte öğrenelim!",
              CharacterPersona.NERDY to
                "Merhaba, ben Nerdy! Her kelimenin gizli bir tarihi olduğunu biliyor muydun? Hadi keşfedelim!",
              CharacterPersona.SECRET_AGENT to
                "Ajan göreve hazır. Bugünkü görevin: yeni bir dil öğrenmek, sessizce ve verimli bir şekilde.",
              CharacterPersona.WIZARD to
                "Selamlar, genç çırak! Öğrendiğin her kelime yeni bir büyü. Başlayalım mı?",
            ),
        ),
      "vi" to
        SpokenPhraseSet(
          greeting = "Xin chào! Hôm nay bạn muốn học về điều gì?",
          fillers =
            listOf(
              "Để xem nào…",
              "Hmm, để mình xem thử…",
              "Cùng xem cái này nhé…",
              "Nhìn cái này thì…",
              "Chờ chút, để xem nào…",
              "Hmm, thú vị đấy…",
              "Xem chúng ta có gì ở đây nào…",
            ),
          personaPreviews =
            mapOf(
              CharacterPersona.BUDDY to "Xin chào! Mình là Buddy! Cùng học nhé!",
              CharacterPersona.NERDY to
                "Chào bạn, mình là Nerdy! Bạn có biết mỗi từ đều có một lịch sử bí mật không? Cùng khám phá nào!",
              CharacterPersona.SECRET_AGENT to
                "Điệp viên sẵn sàng. Nhiệm vụ hôm nay: học một ngôn ngữ mới, thật kín đáo và hiệu quả.",
              CharacterPersona.WIZARD to
                "Chào mừng con, học trò trẻ của ta! Mỗi từ con học được là một câu thần chú mới. Chúng ta bắt đầu nhé?",
            ),
        ),
      "zh" to
        SpokenPhraseSet(
          greeting = "你好！今天想学点什么呢？",
          fillers =
            listOf("让我看看…", "嗯，我来看一下…", "我们来看看这个…", "这个嘛…", "稍等，让我看看…", "嗯，真有意思…", "我们来看看这里有什么…"),
          personaPreviews =
            mapOf(
              CharacterPersona.BUDDY to "嗨！我是巴迪！我们一起学习吧！",
              CharacterPersona.NERDY to "你好，我是纳迪！你知道每个词都有一段秘密的历史吗？我们一起来探个究竟吧！",
              CharacterPersona.SECRET_AGENT to "特工待命。今天的任务：安静而高效地学习一门新语言。",
              CharacterPersona.WIZARD to "欢迎你，年轻的学徒！你学会的每个词都是一道新咒语。我们开始吧？",
            ),
        ),
    )

  /**
   * Returns the [SpokenPhraseSet] for the ISO 639-1 [languageCode], or the English set when the
   * code has none.
   */
  fun forLanguageCode(languageCode: String): SpokenPhraseSet {
    val isoCode = languageCode.trim().replace('_', '-').substringBefore('-').lowercase()
    return phrasesByLanguageCode[isoCode] ?: phrasesByLanguageCode.getValue(FALLBACK_LANGUAGE_CODE)
  }

  /**
   * Returns [persona]'s localized voice-preview greeting in [languageCode] (BCP-47 tag or ISO 639-1
   * code).
   */
  fun personaGreeting(languageCode: String, persona: CharacterPersona): String =
    forLanguageCode(languageCode).personaPreviews.getValue(persona)

  /**
   * Picks the phrases to speak for [nativeLanguage] with a TTS engine that supports
   * [supportedLocaleTags].
   *
   * Uses the native-language set unless the engine reports its locales and none of them is in
   * [nativeLanguage]; then it uses the English set with an `en-US` voice, so the student hears a
   * coherent line instead of one read with the wrong voice. An empty [supportedLocaleTags] means
   * the engine's locales are unknown, and the native language is assumed to be supported.
   */
  fun resolve(
    nativeLanguage: Language,
    supportedLocaleTags: Collection<String>,
  ): ResolvedSpokenPhrases {
    // Prefer the engine's own tag (e.g. pt-BR) over the language's default locale (pt-PT).
    val engineLanguageTag =
      resolveEngineLocaleTagForLanguage(nativeLanguage, supportedLocaleTags)
        ?: return ResolvedSpokenPhrases(
          phrases = forLanguageCode(FALLBACK_LANGUAGE_CODE),
          languageTag = FALLBACK_LANGUAGE_TAG,
        )
    return ResolvedSpokenPhrases(
      phrases = forLanguageCode(nativeLanguage.toIsoLanguageCode()),
      languageTag = engineLanguageTag,
    )
  }
}
