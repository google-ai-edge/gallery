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

package com.google.ai.edge.gallery.claw

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.BatteryManager
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import com.google.gson.JsonObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "ClawTools"

/**
 * Executes tool calls from the LLM. Each tool maps to an Android Intent or
 * system API, keeping the LLM's job simple: just output the right JSON.
 *
 * Returns a human-readable result string for the LLM to use in its response.
 */
object ClawTools {

  fun execute(context: Context, tool: String, args: JsonObject): String {
    return try {
      when (tool) {
        "get_time" -> getTime()
        "get_date" -> getDate()
        "get_battery" -> getBattery(context)
        "open_app" -> openApp(context, args)
        "web_search" -> webSearch(context, args)
        "open_url" -> openUrl(context, args)
        "set_alarm" -> setAlarm(context, args)
        "set_timer" -> setTimer(context, args)
        "create_note" -> createNote(context, args)
        "send_sms" -> sendSms(context, args)
        "make_call" -> makeCall(context, args)
        "open_settings" -> openSettings(context, args)
        "take_photo" -> takePhoto(context)
        "create_calendar_event" -> createCalendarEvent(context, args)
        "open_contacts" -> openContacts(context)
        "set_volume" -> "Volume control requires system permissions. Please adjust manually."
        else -> "Unknown tool: $tool"
      }
    } catch (e: Exception) {
      Log.e(TAG, "Tool '$tool' failed: ${e.message}", e)
      "Error: ${e.message}"
    }
  }

  /** All available tools as a compact description for the system prompt. */
  val toolDescriptions = """
Available tools (respond with JSON):
- {"tool":"get_time"} → current time
- {"tool":"get_date"} → current date
- {"tool":"get_battery"} → battery level
- {"tool":"open_app","args":{"name":"WeChat"}} → open an app by name
- {"tool":"web_search","args":{"query":"weather today"}} → search the web
- {"tool":"open_url","args":{"url":"https://example.com"}} → open a URL
- {"tool":"set_alarm","args":{"hour":8,"minute":30,"message":"Wake up"}} → set alarm
- {"tool":"set_timer","args":{"seconds":300,"message":"Timer"}} → set countdown timer
- {"tool":"create_note","args":{"title":"Shopping","content":"Buy milk and eggs"}} → create a note
- {"tool":"send_sms","args":{"to":"10086","message":"Hello"}} → send SMS
- {"tool":"make_call","args":{"to":"10086"}} → make a phone call
- {"tool":"open_settings","args":{"page":"wifi"}} → open settings (wifi/bluetooth/display/battery/sound)
- {"tool":"take_photo"} → open camera
- {"tool":"create_calendar_event","args":{"title":"Meeting","begin":"2026-04-15 14:00","end":"2026-04-15 15:00"}} → calendar event
- {"tool":"open_contacts"} → open contacts
- {"tool":"reply","args":{"message":"Here is my answer"}} → reply to user without using a tool""".trimIndent()

  // ─── Tool implementations ─────────────────────────────────────────

  private fun getTime(): String {
    val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    return "Current time: ${fmt.format(Date())}"
  }

  private fun getDate(): String {
    val fmt = SimpleDateFormat("yyyy-MM-dd EEEE", Locale.getDefault())
    return "Current date: ${fmt.format(Date())}"
  }

  private fun getBattery(context: Context): String {
    val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    val charging = bm.isCharging
    return "Battery: ${level}%${if (charging) " (charging)" else ""}"
  }

  private fun openApp(context: Context, args: JsonObject): String {
    val name = args.get("name")?.asString ?: return "Missing app name"
    val pm = context.packageManager
    val nameLower = name.lowercase()

    // 1. Common app name → package mapping (Chinese + English)
    val commonApps = mapOf(
      "wechat" to "com.tencent.mm",
      "微信" to "com.tencent.mm",
      "qq" to "com.tencent.mobileqq",
      "alipay" to "com.eg.android.AlipayGphone",
      "支付宝" to "com.eg.android.AlipayGphone",
      "taobao" to "com.taobao.taobao",
      "淘宝" to "com.taobao.taobao",
      "douyin" to "com.ss.android.ugc.aweme",
      "抖音" to "com.ss.android.ugc.aweme",
      "tiktok" to "com.ss.android.ugc.aweme",
      "bilibili" to "tv.danmaku.bili",
      "b站" to "tv.danmaku.bili",
      "chrome" to "com.android.chrome",
      "settings" to "com.android.settings",
      "设置" to "com.android.settings",
      "camera" to "com.android.camera",
      "相机" to "com.android.camera",
      "calculator" to "com.miui.calculator",
      "计算器" to "com.miui.calculator",
      "clock" to "com.android.deskclock",
      "时钟" to "com.android.deskclock",
      "闹钟" to "com.android.deskclock",
      "notes" to "com.miui.notes",
      "记事本" to "com.miui.notes",
      "便签" to "com.miui.notes",
      "phone" to "com.android.phone",
      "电话" to "com.android.phone",
      "messages" to "com.android.mms",
      "短信" to "com.android.mms",
      "feishu" to "com.ss.android.lark",
      "飞书" to "com.ss.android.lark",
      "gallery" to "com.miui.gallery",
      "相册" to "com.miui.gallery",
      "map" to "com.autonavi.minimap",
      "地图" to "com.autonavi.minimap",
      "高德" to "com.autonavi.minimap",
      "百度" to "com.baidu.searchbox",
      "jingdong" to "com.jingdong.app.mall",
      "京东" to "com.jingdong.app.mall",
      "meituan" to "com.sankuai.meituan",
      "美团" to "com.sankuai.meituan",
      "xiaohongshu" to "com.xingin.xhs",
      "小红书" to "com.xingin.xhs",
      "weibo" to "com.sina.weibo",
      "微博" to "com.sina.weibo",
      "spotify" to "com.spotify.music",
      "youtube" to "com.google.android.youtube",
      "twitter" to "com.twitter.android",
      "x" to "com.twitter.android",
      "telegram" to "org.telegram.messenger",
      "file manager" to "com.mi.android.globalFileexplorer",
      "文件管理" to "com.mi.android.globalFileexplorer",
    )

    // Try common name first
    val pkg = commonApps[nameLower]
    if (pkg != null) {
      return tryLaunchPackage(context, pm, pkg, name)
    }

    // 2. Fuzzy match by label (contains, not exact)
    val apps = pm.getInstalledApplications(0)
    val match = apps.find {
      val label = pm.getApplicationLabel(it).toString()
      label.equals(name, ignoreCase = true) || label.contains(name, ignoreCase = true)
    }
    if (match != null) {
      return tryLaunchPackage(context, pm, match.packageName, pm.getApplicationLabel(match).toString())
    }

    return "App '$name' not found on this device."
  }

  private fun tryLaunchPackage(context: Context, pm: android.content.pm.PackageManager, pkg: String, displayName: String): String {
    try {
      val intent = pm.getLaunchIntentForPackage(pkg)
      if (intent != null) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return "Opened $displayName"
      }
    } catch (e: Exception) {
      Log.w(TAG, "getLaunchIntent failed for $pkg: ${e.message}")
    }

    // Fallback: try component-based launch
    try {
      val intent = Intent(Intent.ACTION_MAIN).apply {
        addCategory(Intent.CATEGORY_LAUNCHER)
        setPackage(pkg)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      val resolveInfo = pm.queryIntentActivities(intent, 0)
      if (resolveInfo.isNotEmpty()) {
        val activity = resolveInfo[0].activityInfo
        intent.setClassName(activity.packageName, activity.name)
        context.startActivity(intent)
        return "Opened $displayName"
      }
    } catch (e: Exception) {
      Log.w(TAG, "Fallback launch failed for $pkg: ${e.message}")
    }

    return "App '$displayName' ($pkg) is installed but cannot be launched."
  }

  private fun webSearch(context: Context, args: JsonObject): String {
    val query = args.get("query")?.asString ?: return "Missing search query"
    val intent = Intent(Intent.ACTION_WEB_SEARCH).apply {
      putExtra(SearchManager.QUERY, query)
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
    return "Searching for: $query"
  }

  private fun openUrl(context: Context, args: JsonObject): String {
    val url = args.get("url")?.asString ?: return "Missing URL"
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
    return "Opened: $url"
  }

  private fun setAlarm(context: Context, args: JsonObject): String {
    val hour = args.get("hour")?.asInt ?: return "Missing hour"
    val minute = args.get("minute")?.asInt ?: 0
    val message = args.get("message")?.asString ?: "Alarm"
    val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
      putExtra(AlarmClock.EXTRA_HOUR, hour)
      putExtra(AlarmClock.EXTRA_MINUTES, minute)
      putExtra(AlarmClock.EXTRA_MESSAGE, message)
      putExtra(AlarmClock.EXTRA_SKIP_UI, true)
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
    return "Alarm set for $hour:${"%02d".format(minute)} — $message"
  }

  private fun setTimer(context: Context, args: JsonObject): String {
    val seconds = args.get("seconds")?.asInt ?: return "Missing seconds"
    val message = args.get("message")?.asString ?: "Timer"
    val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
      putExtra(AlarmClock.EXTRA_LENGTH, seconds)
      putExtra(AlarmClock.EXTRA_MESSAGE, message)
      putExtra(AlarmClock.EXTRA_SKIP_UI, true)
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
    return "Timer set for ${seconds}s — $message"
  }

  private fun createNote(context: Context, args: JsonObject): String {
    val title = args.get("title")?.asString ?: ""
    val content = args.get("content")?.asString ?: ""
    val intent = Intent(Intent.ACTION_SEND).apply {
      type = "text/plain"
      putExtra(Intent.EXTRA_SUBJECT, title)
      putExtra(Intent.EXTRA_TEXT, content)
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    // Try MIUI Notes first
    try {
      intent.setPackage("com.miui.notes")
      context.startActivity(intent)
      return "Note created: $title"
    } catch (_: Exception) {}
    // Fallback to chooser
    context.startActivity(Intent.createChooser(intent, "Create note").apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    })
    return "Note created: $title"
  }

  private fun sendSms(context: Context, args: JsonObject): String {
    val to = args.get("to")?.asString ?: return "Missing phone number"
    val message = args.get("message")?.asString ?: ""
    val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$to")).apply {
      putExtra("sms_body", message)
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
    return "SMS to $to prepared (confirm in messaging app)"
  }

  private fun makeCall(context: Context, args: JsonObject): String {
    val to = args.get("to")?.asString ?: return "Missing phone number"
    val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$to")).apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
    return "Calling $to (confirm on dial screen)"
  }

  private fun openSettings(context: Context, args: JsonObject): String {
    val page = args.get("page")?.asString?.lowercase() ?: "main"
    val action = when (page) {
      "wifi", "network" -> Settings.ACTION_WIFI_SETTINGS
      "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
      "display", "brightness" -> Settings.ACTION_DISPLAY_SETTINGS
      "battery", "power" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
      "sound", "volume" -> Settings.ACTION_SOUND_SETTINGS
      "location", "gps" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
      "apps", "application" -> Settings.ACTION_APPLICATION_SETTINGS
      "storage" -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS
      "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
      else -> Settings.ACTION_SETTINGS
    }
    context.startActivity(Intent(action).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
    return "Opened settings: $page"
  }

  private fun takePhoto(context: Context): String {
    val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
    return "Camera opened"
  }

  private fun createCalendarEvent(context: Context, args: JsonObject): String {
    val title = args.get("title")?.asString ?: return "Missing title"
    val beginStr = args.get("begin")?.asString
    val endStr = args.get("end")?.asString

    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    val beginMs = if (beginStr != null) fmt.parse(beginStr)?.time ?: System.currentTimeMillis() else System.currentTimeMillis()
    val endMs = if (endStr != null) fmt.parse(endStr)?.time ?: (beginMs + 3600000) else (beginMs + 3600000)

    val intent = Intent(Intent.ACTION_INSERT).apply {
      data = CalendarContract.Events.CONTENT_URI
      putExtra(CalendarContract.Events.TITLE, title)
      putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, beginMs)
      putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endMs)
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
    return "Calendar event created: $title"
  }

  private fun openContacts(context: Context): String {
    val intent = Intent(Intent.ACTION_VIEW).apply {
      data = ContactsContract.Contacts.CONTENT_URI
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
    return "Contacts opened"
  }
}
