package android.content

import android.net.Uri
import android.os.Bundle

class ActivityNotFoundException(message: String? = null) : Exception(message)

class Intent {
  var action: String? = null
  var data: Uri? = null
  var type: String? = null
  val extras: Bundle = Bundle()
  val categories: MutableSet<String> = mutableSetOf()
  var flags: Int = 0
  var clipData: Any? = null

  constructor()
  constructor(action: String) { this.action = action }
  constructor(action: String, uri: Uri) {
    this.action = action
    this.data = uri
  }
  constructor(context: Context, cls: Class<*>? = null) : this()

  fun setData(uri: Uri): Intent {
    this.data = uri
    return this
  }

  fun setType(type: String): Intent {
    this.type = type
    return this
  }

  fun addCategory(category: String): Intent {
    categories.add(category)
    return this
  }

  fun addFlags(flags: Int): Intent {
    this.flags = this.flags or flags
    return this
  }

  fun putExtra(name: String, value: String?): Intent {
    extras.putString(name, value)
    return this
  }

  fun putExtra(name: String, value: Boolean): Intent {
    extras.putBoolean(name, value)
    return this
  }

  fun putExtra(name: String, value: Int): Intent {
    extras.putInt(name, value)
    return this
  }

  fun putExtra(name: String, value: Long): Intent {
    extras.putLong(name, value)
    return this
  }

  fun putExtra(name: String, value: Any?): Intent {
    if (value is String) extras.putString(name, value)
    return this
  }

  fun getBooleanExtra(name: String, defaultValue: Boolean): Boolean =
    extras.getBoolean(name, defaultValue)

  fun getStringExtra(name: String): String? = extras.getString(name)

  fun getIntExtra(name: String, defaultValue: Int): Int = extras.getInt(name, defaultValue)

  companion object {
    const val ACTION_VIEW = "android.intent.action.VIEW"
    const val ACTION_OPEN_DOCUMENT = "android.intent.action.OPEN_DOCUMENT"
    const val ACTION_GET_CONTENT = "android.intent.action.GET_CONTENT"
    const val ACTION_SEND = "android.intent.action.SEND"
    const val ACTION_SENDTO = "android.intent.action.SENDTO"
    const val ACTION_BOOT_COMPLETED = "android.intent.action.BOOT_COMPLETED"
    const val ACTION_INSERT = "android.intent.action.INSERT"
    const val CATEGORY_OPENABLE = "android.intent.category.OPENABLE"
    const val EXTRA_ALLOW_MULTIPLE = "android.intent.extra.ALLOW_MULTIPLE"
    const val EXTRA_EMAIL = "android.intent.extra.EMAIL"
    const val EXTRA_SUBJECT = "android.intent.extra.SUBJECT"
    const val EXTRA_TEXT = "android.intent.extra.TEXT"
    const val EXTRA_MIME_TYPES = "android.intent.extra.MIME_TYPES"
    const val EXTRA_STREAM = "android.intent.extra.STREAM"
    const val FLAG_ACTIVITY_NEW_TASK = 0x10000000
    const val FLAG_ACTIVITY_CLEAR_TOP = 0x04000000
    const val FLAG_GRANT_READ_URI_PERMISSION = 1
    const val FLAG_GRANT_PERSISTABLE_URI_PERMISSION = 64

    fun createChooser(target: Intent, title: CharSequence?): Intent = target
  }
}

