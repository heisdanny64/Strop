package expo.modules.tavibridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class TaviForegroundService : Service() {
  companion object {
    const val CHANNEL_ID = "tavi-proot"
    const val NOTIFICATION_ID = 1001
    const val ACTION_START = "expo.modules.tavibridge.START"
    const val ACTION_STOP = "expo.modules.tavibridge.STOP"
    const val ACTION_UPDATE = "expo.modules.tavibridge.UPDATE"

    fun start(context: Context) {
      val intent = Intent(context, TaviForegroundService::class.java).setAction(ACTION_START)
      context.startForegroundService(intent)
    }

    fun stop(context: Context) {
      context.stopService(Intent(context, TaviForegroundService::class.java))
    }

    fun update(context: Context, message: String) {
      val intent = Intent(context, TaviForegroundService::class.java)
        .setAction(ACTION_UPDATE)
        .putExtra("message", message)
      context.startForegroundService(intent)
    }
  }

  override fun onCreate() {
    super.onCreate()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel = NotificationChannel(CHANNEL_ID, "Linux environment", NotificationManager.IMPORTANCE_LOW)
      val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
      manager.createNotificationChannel(channel)
    }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val action = intent?.action ?: ACTION_START
    when (action) {
      ACTION_STOP -> {
        stopSelf()
        return START_NOT_STICKY
      }
      ACTION_UPDATE -> {
        val message = intent?.getStringExtra("message") ?: "Linux environment running"
        updateNotification(message)
        return START_STICKY
      }
      else -> {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
          .setContentTitle("Tavi")
          .setContentText("Linux environment running")
          .setSmallIcon(android.R.drawable.stat_notify_sync)
          .setOngoing(true)
          .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
          startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
          startForeground(NOTIFICATION_ID, notification)
        }
        return START_STICKY
      }
    }
  }

  private fun updateNotification(message: String) {
    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val notification = NotificationCompat.Builder(this, CHANNEL_ID)
      .setContentTitle("Tavi")
      .setContentText(message)
      .setSmallIcon(android.R.drawable.stat_notify_sync)
      .setOngoing(true)
      .build()
    manager.notify(NOTIFICATION_ID, notification)
  }

  override fun onBind(intent: Intent?): IBinder? = null
}
