package android.provider

object ContactsContract {
  object Intents {
    object Insert {
      const val ACTION = "android.intent.action.INSERT"
      const val NAME = "name"
      const val EMAIL = "email"
      const val EMAIL_TYPE = "email_type"
      const val PHONE = "phone"
      const val PHONE_TYPE = "phone_type"
    }
  }

  object RawContacts {
    const val CONTENT_TYPE = "vnd.android.cursor.dir/raw_contact"
  }

  object CommonDataKinds {
    object Email {
      const val TYPE_WORK = 2
    }
    object Phone {
      const val TYPE_WORK = 3
    }
  }
}

object CalendarContract {
  const val EXTRA_EVENT_BEGIN_TIME = "beginTime"
  const val EXTRA_EVENT_END_TIME = "endTime"

  object Events {
    val CONTENT_URI = android.net.Uri.parse("content://com.android.calendar/events")
    const val TITLE = "title"
    const val DESCRIPTION = "description"
  }

  object Instances {
    val CONTENT_URI = android.net.Uri.parse("content://com.android.calendar/instances/when")
    const val TITLE = "title"
    const val DESCRIPTION = "description"
    const val BEGIN = "begin"
    const val END = "end"
  }
}

object Settings {
  const val ACTION_SETTINGS = "android.settings.SETTINGS"
  const val ACTION_WIFI_SETTINGS = "android.settings.WIFI_SETTINGS"
}
