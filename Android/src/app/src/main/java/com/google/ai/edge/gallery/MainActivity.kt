private fun startApiServer() {
  try {
    apiServer = LiteRtApiServer(this, 8080)
    apiServer?.startServer()

    var ipAddress = "无法获取IP"
    try {
      // 用 NetworkInterface 遍历网络接口获取 IP，不需要额外权限
      val networkInterfaces = java.net.NetworkInterface.getNetworkInterfaces()
      while (networkInterfaces.hasMoreElements()) {
        val networkInterface = networkInterfaces.nextElement()
        if (networkInterface.isLoopback || !networkInterface.isUp) continue
        val addresses = networkInterface.inetAddresses
        while (addresses.hasMoreElements()) {
          val address = addresses.nextElement()
          if (address is java.net.Inet4Address && !address.isLoopbackAddress) {
            ipAddress = address.hostAddress ?: "未知"
            break
          }
        }
        if (ipAddress != "无法获取IP") break
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to get IP: ${e.message}")
    }

    val serverUrl = "http://$ipAddress:8080/health"
    Log.d(TAG, "LiteRT API Server running at $serverUrl")

    // 创建持久通知显示服务器地址
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel = NotificationChannel(
        "api_server",
        "API Server",
        NotificationManager.IMPORTANCE_LOW
      )
      val notificationManager = getSystemService(NotificationManager::class.java)
      notificationManager.createNotificationChannel(channel)

      val notification = android.app.Notification.Builder(this, "api_server")
        .setContentTitle("API Server 运行中")
        .setContentText(serverUrl)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setOngoing(true)
        .build()

      notificationManager.notify(1, notification)
    }

    android.widget.Toast.makeText(this, "API Server:\n$serverUrl", android.widget.Toast.LENGTH_LONG).show()
  } catch (e: Exception) {
    Log.e(TAG, "Failed to start API Server: ${e.message}")
    android.widget.Toast.makeText(this, "API Server 启动失败", android.widget.Toast.LENGTH_SHORT).show()
  }
}
