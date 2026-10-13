package expo.modules.tavibridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

class TaviForegroundService : Service() {
  companion object {
    const val CHANNEL_ID = "tavi-proot"
    const val NOTIFICATION_ID = 1001

    fun start(context: Context, message: String = "Linux environment running") {
      val intent = Intent(context, TaviForegroundService::class.java)
      context.startForegroundService(intent)
      val notification = buildNotification(message)
      // startForeground must run on the main thread and immediately after
      // startForegroundService, or Android 14/15 throws
      // ForegroundServiceDidNotStartInTimeException.
      Handler(Looper.getMainLooper()).post { context.startForeground(NOTIFICATION_ID, notification) }
    }

    fun stop(context: Context) {
      context.stopService(Intent(context, TaviForegroundService::class.java))
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
    // If the system restarted this service, promote it back to the foreground.
    Handler(Looper.getMainLooper()).post {
      val notification = buildNotification("Linux environment running")
      context.startForeground(NOTIFICATION_ID, notification)
    }
    return START_STICKY
  }

  private fun buildNotification(message: String): Notification {
    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setContentTitle("Strop")
      .setContentText(message)
      .setSmallIcon(android.R.drawable.stat_notify_sync)
      .setOngoing(true)
      .setPriority(NotificationManagerCompat.IMPORTANCE_MIN)
      .build()
  }

  override fun onBind(intent: Intent?): IBinder? = null
}
