package com.google.ai.edge.gallery

import android.app.Service
import android.content.Intent
import android.os.IBinder

// TODO(jingjin): implement foreground service.
class GalleryService : Service() {
  override fun onBind(p0: Intent?): IBinder? {
    return null
  }
}