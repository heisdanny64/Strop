package expo.modules.stropbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

class StropForegroundService : Service() {
  companion object {
    const val CHANNEL_ID = "strop-proot"
    private const val LEGACY_CHANNEL_ID = "tavi-proot"
    const val NOTIFICATION_ID = 1001
    const val KEY_MESSAGE = "expo.modules.stropbridge.EXTRA_MESSAGE"

    fun start(context: Context, message: String) {
      val intent = Intent(context, StropForegroundService::class.java)
        .putExtra(KEY_MESSAGE, message)
      context.startForegroundService(intent)
    }

    fun stop(context: Context) {
      context.stopService(Intent(context, StropForegroundService::class.java))
    }
  }

  override fun onCreate() {
    super.onCreate()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel = NotificationChannel(CHANNEL_ID, "Linux environment", NotificationManager.IMPORTANCE_LOW)
      val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
      manager.deleteNotificationChannel(LEGACY_CHANNEL_ID)
      manager.createNotificationChannel(channel)
    }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent == null) {
      stopSelf()
      return START_NOT_STICKY
    }
    val message = intent.getStringExtra(KEY_MESSAGE) ?: "Linux environment running"

    val notification = NotificationCompat.Builder(this, CHANNEL_ID)
      .setContentTitle("Strop")
      .setContentText(message)
      .setSmallIcon(android.R.drawable.stat_notify_sync)
      .setOngoing(true)
      .setPriority(NotificationCompat.PRIORITY_LOW)
      .build()

    // Called synchronously on the main thread — Android requires this
    // within ~5s of startForegroundService(), or it throws.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    } else {
      startForeground(NOTIFICATION_ID, notification)
    }
    return START_STICKY
  }

  override fun onTimeout(type: Int, reason: Int) {
    stopSelf()
  }

  override fun onBind(intent: Intent?): IBinder? = null
}
