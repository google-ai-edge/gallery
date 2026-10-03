import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.protobuf)
}

java {
  sourceCompatibility = JavaVersion.VERSION_21
  targetCompatibility = JavaVersion.VERSION_21
}

val generatedRDir: File = layout.buildDirectory.dir("generated/androidR/kotlin").get().asFile

kotlin {
  compilerOptions {
    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    freeCompilerArgs.addAll(
      "-Xskip-metadata-version-check",
      "-opt-in=kotlin.io.encoding.ExperimentalEncodingApi",
      "-opt-in=androidx.compose.ui.ExperimentalComposeUiApi",
      "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
      "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi"
    )
  }
  sourceSets.getByName("main") {
    kotlin.srcDirs("src/main/kotlin", "src/main/java", "../app/src/main/java", generatedRDir)
    kotlin.exclude("**/MainActivity.kt")
    kotlin.exclude("**/GalleryApplication.kt")
    kotlin.exclude("**/FcmMessagingService.kt")
    kotlin.exclude("**/AICoreModelHelper.kt")
    kotlin.exclude("**/worker/**")
    kotlin.exclude("**/di/AppModule.kt")
    kotlin.exclude("**/DownloadRepository.kt")
    kotlin.exclude("**/customtasks/scrapbook/**")
  }
}

// ---------------------------------------------------------------------------------------------
// Android resources -> desktop.
//
// The shared UI code references `R.string.*`, `R.plurals.*`, `R.dimen.*`, `R.drawable.*` and
// `R.font.*`. Instead of a hand-maintained copy, generate `R` from the upstream `res/` XML so the
// desktop build always shows exactly the same text/dimensions as Android.
// ---------------------------------------------------------------------------------------------
val androidResDir = file("../app/src/main/res")

val generateAndroidR by tasks.registering {
  val valuesDir = File(androidResDir, "values")
  val drawableDir = File(androidResDir, "drawable")
  val fontDir = File(androidResDir, "font")
  inputs.dir(valuesDir)
  inputs.dir(drawableDir)
  inputs.dir(fontDir)
  outputs.dir(generatedRDir)
  doLast {
    fun androidUnescape(raw: String): String {
      val sb = StringBuilder()
      var inQuotes = false
      var lastWasSpace = false
      var i = 0
      while (i < raw.length) {
        val c = raw[i]
        if (c == '\\' && i + 1 < raw.length) {
          val n = raw[i + 1]
          when (n) {
            'n' -> sb.append('\n')
            't' -> sb.append('\t')
            'u' -> {
              if (i + 5 < raw.length) {
                sb.append(raw.substring(i + 2, i + 6).toInt(16).toChar())
                i += 4
              }
            }
            else -> sb.append(n)
          }
          i += 2
          lastWasSpace = false
          continue
        }
        if (c == '"') {
          inQuotes = !inQuotes
          i++
          continue
        }
        if (!inQuotes && c.isWhitespace()) {
          if (!lastWasSpace) sb.append(' ')
          lastWasSpace = true
          i++
          continue
        }
        sb.append(c)
        lastWasSpace = false
        i++
      }
      return sb.toString().trim()
    }

    fun kotlinLiteral(s: String): String {
      val sb = StringBuilder("\"")
      for (ch in s) {
        when (ch) {
          '\\' -> sb.append("\\\\")
          '"' -> sb.append("\\\"")
          '$' -> sb.append("\\$")
          '\n' -> sb.append("\\n")
          '\r' -> sb.append("\\r")
          '\t' -> sb.append("\\t")
          else -> sb.append(ch)
        }
      }
      return sb.append('"').toString()
    }

    val dbf = javax.xml.parsers.DocumentBuilderFactory.newInstance()
    val strings = linkedMapOf<String, String>()
    val plurals = linkedMapOf<String, Map<String, String>>()
    val dimens = linkedMapOf<String, Float>()
    valuesDir.listFiles { f -> f.extension == "xml" }!!.sortedBy { it.name }.forEach { xml ->
      val doc = dbf.newDocumentBuilder().parse(xml)
      val root = doc.documentElement
      val children = root.childNodes
      for (idx in 0 until children.length) {
        val node = children.item(idx) as? org.w3c.dom.Element ?: continue
        val name = node.getAttribute("name")
        when (node.tagName) {
          "string" -> strings[name] = androidUnescape(node.textContent)
          "plurals" -> {
            val items = linkedMapOf<String, String>()
            val itemNodes = node.getElementsByTagName("item")
            for (j in 0 until itemNodes.length) {
              val item = itemNodes.item(j) as org.w3c.dom.Element
              items[item.getAttribute("quantity")] = androidUnescape(item.textContent)
            }
            plurals[name] = items
          }
          "dimen" -> {
            val v = node.textContent.trim()
            val num = v.removeSuffix("dp").removeSuffix("dip").removeSuffix("sp").removeSuffix("px")
            num.toFloatOrNull()?.let { dimens[name] = it }
          }
        }
      }
    }
    val drawables = drawableDir.listFiles()!!.map { it.nameWithoutExtension }.sorted()
    val fonts = fontDir.listFiles()!!.map { it.nameWithoutExtension }.sorted()

    val out = StringBuilder()
    out.appendLine("// GENERATED FILE - DO NOT EDIT. Generated by :desktop:generateAndroidR from app/src/main/res.")
    out.appendLine("package com.google.ai.edge.gallery")
    out.appendLine()
    out.appendLine("@Suppress(\"ClassName\", \"ObjectPropertyName\")")
    out.appendLine("object R {")
    var nextId = 0x7f010000
    val stringIds = linkedMapOf<String, Int>()
    out.appendLine("  object string {")
    strings.keys.forEach { n -> val id = nextId++; stringIds[n] = id; out.appendLine("    const val $n: Int = $id") }
    out.appendLine("  }")
    nextId = 0x7f020000
    val pluralIds = linkedMapOf<String, Int>()
    out.appendLine("  object plurals {")
    plurals.keys.forEach { n -> val id = nextId++; pluralIds[n] = id; out.appendLine("    const val $n: Int = $id") }
    out.appendLine("  }")
    nextId = 0x7f030000
    val dimenIds = linkedMapOf<String, Int>()
    out.appendLine("  object dimen {")
    dimens.keys.forEach { n -> val id = nextId++; dimenIds[n] = id; out.appendLine("    const val $n: Int = $id") }
    out.appendLine("  }")
    nextId = 0x7f040000
    val drawableIds = linkedMapOf<String, Int>()
    out.appendLine("  object drawable {")
    drawables.forEach { n -> val id = nextId++; drawableIds[n] = id; out.appendLine("    const val $n: Int = $id") }
    out.appendLine("  }")
    nextId = 0x7f050000
    val fontIds = linkedMapOf<String, Int>()
    out.appendLine("  object font {")
    fonts.forEach { n -> val id = nextId++; fontIds[n] = id; out.appendLine("    const val $n: Int = $id") }
    out.appendLine("  }")
    out.appendLine()
    out.appendLine("  private val stringValues: Map<Int, String> by lazy { hashMapOf(")
    strings.forEach { (n, v) -> out.appendLine("    ${stringIds[n]} to ${kotlinLiteral(v)},") }
    out.appendLine("  ) }")
    out.appendLine("  private val pluralValues: Map<Int, Map<String, String>> by lazy { hashMapOf(")
    plurals.forEach { (n, items) ->
      out.appendLine("    ${pluralIds[n]} to mapOf(" + items.entries.joinToString(", ") { "${kotlinLiteral(it.key)} to ${kotlinLiteral(it.value)}" } + "),")
    }
    out.appendLine("  ) }")
    out.appendLine("  private val dimenValues: Map<Int, Float> = hashMapOf(")
    dimens.forEach { (n, v) -> out.appendLine("    ${dimenIds[n]} to ${v}f,") }
    out.appendLine("  )")
    out.appendLine("  private val drawableNames: Map<Int, String> = hashMapOf(")
    drawables.forEach { n -> out.appendLine("    ${drawableIds[n]} to ${kotlinLiteral(n)},") }
    out.appendLine("  )")
    out.appendLine("  private val fontNames: Map<Int, String> = hashMapOf(")
    fonts.forEach { n -> out.appendLine("    ${fontIds[n]} to ${kotlinLiteral(n)},") }
    out.appendLine("  )")
    out.appendLine()
    out.appendLine("  private fun format(template: String, args: Array<out Any>): String =")
    out.appendLine("    if (args.isEmpty()) template else runCatching { java.lang.String.format(template, *args) }.getOrDefault(template)")
    out.appendLine()
    out.appendLine("  fun getString(resId: Int, vararg formatArgs: Any): String =")
    out.appendLine("    format(stringValues[resId] ?: error(\"Unknown string resource id: \$resId\"), formatArgs)")
    out.appendLine()
    out.appendLine("  /** English plural rules (the only locale shipped by upstream): one vs other. */")
    out.appendLine("  fun getQuantityString(resId: Int, quantity: Int, vararg formatArgs: Any): String {")
    out.appendLine("    val items = pluralValues[resId] ?: error(\"Unknown plurals resource id: \$resId\")")
    out.appendLine("    val template = (if (quantity == 1) items[\"one\"] else null) ?: items[\"other\"] ?: items.values.first()")
    out.appendLine("    return format(template, if (formatArgs.isEmpty()) arrayOf(quantity) else formatArgs)")
    out.appendLine("  }")
    out.appendLine()
    out.appendLine("  fun getDimensionDp(resId: Int): Float = dimenValues[resId] ?: error(\"Unknown dimen resource id: \$resId\")")
    out.appendLine("  fun getDrawableName(resId: Int): String = drawableNames[resId] ?: error(\"Unknown drawable resource id: \$resId\")")
    out.appendLine("  fun getFontName(resId: Int): String = fontNames[resId] ?: error(\"Unknown font resource id: \$resId\")")
    out.appendLine("}")

    val target = File(generatedRDir, "com/google/ai/edge/gallery/R.kt")
    target.parentFile.mkdirs()
    target.writeText(out.toString())
  }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
  dependsOn(generateAndroidR)
}

// Bundle upstream resources on the classpath so the installed app never reads the source tree.
tasks.named<ProcessResources>("processResources") {
  from(File(androidResDir, "drawable")) { into("drawable") }
  from(File(androidResDir, "font")) { into("font") }
  from(File(androidResDir, "mipmap-xxxhdpi")) { into("mipmap") }
  from("../app/src/main/assets") { into("assets") }
  from("../../../model_allowlists") {
    include("1_0_19.json")
    into("model_allowlists")
  }
  // Index of bundled asset files, used by AssetManager.list() (classpath dirs cannot be listed
  // reliably from inside a jar).
  val assetsRoot = file("../app/src/main/assets")
  inputs.dir(assetsRoot)
  doLast {
    val outDir = destinationDir
    val index = assetsRoot.walkTopDown()
      .filter { it.isFile }
      .map { it.relativeTo(assetsRoot).invariantSeparatorsPath }
      .sorted()
      .joinToString("\n")
    File(outDir, "assets/asset_index.txt").apply { parentFile.mkdirs(); writeText(index) }
  }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
  compilerOptions {
    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    freeCompilerArgs.addAll(
      "-Xskip-metadata-version-check",
      "-opt-in=kotlin.io.encoding.ExperimentalEncodingApi",
      "-opt-in=androidx.compose.ui.ExperimentalComposeUiApi",
      "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
      "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi"
    )
  }
}

tasks.withType<JavaCompile>().configureEach {
  sourceCompatibility = "21"
  targetCompatibility = "21"
}

configurations.all {
  resolutionStrategy {
    // The runtime stdlib MUST match the Kotlin compiler version (libs.versions.toml: kotlin).
    // Code compiled by Kotlin 2.2 calls kotlin.coroutines.jvm.internal.SpillingKt from suspend
    // functions; forcing an older stdlib (2.0.21) made every such coroutine die with
    // NoClassDefFoundError, which left the chat screen stuck on "Initializing model".
    val kotlinVersion = libs.versions.kotlin.get()
    force("org.jetbrains.kotlin:kotlin-stdlib:$kotlinVersion")
    force("org.jetbrains.kotlin:kotlin-stdlib-common:$kotlinVersion")
    force("org.jetbrains.kotlin:kotlin-stdlib-jdk8:$kotlinVersion")
    force("org.jetbrains.kotlin:kotlin-stdlib-jdk7:$kotlinVersion")
    force("org.jetbrains.kotlin:kotlin-reflect:$kotlinVersion")
    force("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    force("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.9.0")
    force("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
  }
}

// ---------------------------------------------------------------------------------------------
// LiteRT-LM native library handling.
// ---------------------------------------------------------------------------------------------
val litertlmVersion = "0.17.1"
val litertlmRaw: Configuration by configurations.creating { isTransitive = false }
val litertlmJniPath = "com/google/ai/edge/litertlm/jni/windows-x86_64/litertlm_jni.dll"
layout.buildDirectory.set(file("build3")); val appResourcesDir = layout.buildDirectory.dir("appResources")

val stripLitertlmJar by tasks.registering(Jar::class) {
  archiveFileName.set("litertlm-jvm-$litertlmVersion-nonative.jar")
  destinationDirectory.set(layout.buildDirectory.dir("litertlm"))
  from({ zipTree(litertlmRaw.singleFile) }) { 
    exclude("com/google/ai/edge/litertlm/jni/linux*/**")
    exclude("com/google/ai/edge/litertlm/jni/mac*/**")
  }
}

val extractLitertlmWindowsNatives by tasks.registering(Copy::class) {
  from({ zipTree(litertlmRaw.singleFile) }) {
    include(litertlmJniPath)
    eachFile { path = name }
    includeEmptyDirs = false
  }
  into(appResourcesDir.map { it.dir("windows-x64") })
}

dependencies {
  litertlmRaw("com.google.ai.edge.litertlm:litertlm-jvm:$litertlmVersion")
  // Runtime deps of litertlm-jvm (normally transitive).
  implementation("org.jetbrains.kotlin:kotlin-reflect:2.0.21")

  // JavaFX for WebView
  val javaFxVersion = "17.0.2"
  implementation("org.openjfx:javafx-base:$javaFxVersion:win")
  implementation("org.openjfx:javafx-graphics:$javaFxVersion:win")
  implementation("org.openjfx:javafx-controls:$javaFxVersion:win")
  implementation("org.openjfx:javafx-web:$javaFxVersion:win")
  implementation("org.openjfx:javafx-swing:$javaFxVersion:win")
}

dependencies {
  implementation(compose.desktop.currentOs)
  implementation(compose.material3)
  implementation(compose.materialIconsExtended)
  implementation(compose.components.resources)

  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.com.google.code.gson)
  implementation(libs.commonmark)
  implementation(libs.richtext)
  implementation(libs.mcp.kotlin.sdk)

  implementation("io.ktor:ktor-client-cio:3.0.1")
  implementation("io.ktor:ktor-client-content-negotiation:3.0.1")
  implementation("io.ktor:ktor-serialization-kotlinx-json:3.0.1")
  implementation("org.json:json:20240303")
  implementation("com.google.guava:guava:33.3.1-jre")

  // LiteRT-LM: the published jar bundles JNI libraries for every OS (~380 MB). Use a copy with the
  // natives stripped; the Windows DLL is shipped as an app resource (see litertlmNatives below).
  implementation(files(stripLitertlmJar))

  implementation(libs.protobuf.javalite)
  implementation(libs.protobuf.kotlin.lite)
  implementation(libs.moshi.kotlin)
  implementation(libs.androidx.datastore)

  implementation("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
  implementation("org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
  implementation("org.jetbrains.androidx.navigation:navigation-compose:2.8.0-alpha10")
}

protobuf {
  protoc { artifact = "com.google.protobuf:protoc:4.26.1" }
  generateProtoTasks {
    all().forEach { task ->
      task.builtins {
        named("java") { option("lite") }
        create("kotlin") { option("lite") }
      }
    }
  }
}

sourceSets {
  named("main") {
    proto {
      srcDir("../app/src/main/proto")
    }
  }
}

compose.resources {
  packageOfResClass = "com.google.ai.edge.gallery.desktop.generated.resources"
}

compose.desktop {
  application {
    mainClass = "com.google.ai.edge.gallery.desktop.MainKt"
    nativeDistributions {
      targetFormats(TargetFormat.Exe, TargetFormat.Msi)
      // The jlink runtime must contain every JDK module used at runtime, otherwise the packaged
      // launcher fails with "Failed to launch JVM" (e.g. java.net.http for model downloads).
      modules(
        "java.base",
        "java.datatransfer",
        "java.desktop",
        "java.instrument",
        "java.logging",
        "java.management",
        "java.naming",
        "java.net.http",
        "java.prefs",
        "java.scripting",
        "java.sql",
        "java.xml",
        "jdk.accessibility",
        "jdk.charsets",
        "jdk.crypto.cryptoki",
        "jdk.crypto.ec",
        "jdk.localedata",
        "jdk.net",
        "jdk.unsupported",
        "jdk.zipfs",
      )
      packageName = "AIEdgeGallery"
      // Must be higher than any previously released installer so MSI upgrades replace it.
      packageVersion = "1.1.3"
      description = "Community Windows port of Google AI Edge Gallery"
      vendor = "AI Edge Gallery Windows Community Port"
      copyright = "Copyright 2025 Google LLC. Licensed under the Apache License, Version 2.0."
      licenseFile.set(rootProject.file("../../LICENSE"))
      // Ships litertlm_jni.dll next to the app so it is loaded from the install dir.
      appResourcesRootDir.set(appResourcesDir)
      windows {
        // iconFile.set(project.file("icons/app_icon.ico"))
        menuGroup = "AI Edge Gallery (Community Windows Port)"
        menu = true
        shortcut = true
        dirChooser = true
        perUserInstall = false
        upgradeUuid = "d7cccab0-08a6-4acf-b08c-d79bf1e947e7"
      }
    }
    jvmArgs(
      "-Xss4m",
      "-XX:+UseG1GC",
      "-Dfile.encoding=UTF-8",
      "-Dsun.stdout.encoding=UTF-8",
      "-Dsun.stderr.encoding=UTF-8",
    )
  }
}

// Headless check of the real AI Chat init + inference path, straight from source (no installer).
tasks.register<JavaExec>("runInitSmokeTest") {
  group = "verification"
  dependsOn("classes")
  classpath = sourceSets["main"].runtimeClasspath
  mainClass.set("com.google.ai.edge.gallery.desktop.InitSmokeTestKt")
  jvmArgs("-Xss4m", "-XX:+UseG1GC", "-Dfile.encoding=UTF-8", "-Dsun.stdout.encoding=UTF-8")
  args(
    (project.findProperty("smokeModel") ?: "Gemma-4-E2B-it").toString(),
    (project.findProperty("smokeTask") ?: "llm_chat").toString(),
  )
}


