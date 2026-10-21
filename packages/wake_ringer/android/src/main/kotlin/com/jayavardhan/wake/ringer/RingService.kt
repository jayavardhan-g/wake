package com.jayavardhan.wake.ringer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Foreground service held while [Ringer] rings. Being in the foreground keeps the process
 * from being frozen or killed mid-ring, and lets Android treat the ringing as foreground
 * audio (audio focus is ignored for background apps on recent Android versions).
 *
 * The service doesn't play anything itself: if it can't enter the foreground the alarm still
 * rings, just without those protections.
 */
class RingService : Service() {
  companion object {
    private const val TAG = "WakeRingService"
    private const val CHANNEL_ID = "wake_ringing"
    private const val NOTIFICATION_ID = 7417

    private var instance: RingService? = null
    // stop() was called after start() but before onStartCommand ran.
    private var stopRequested = false
    // Shown instead of the generic "Ringing" notification, e.g. a locked alarm's own.
    private var customNotification: Pair<Int, Notification>? = null

    /**
     * Throws if Android doesn't allow starting a foreground service right now. [notification]
     * replaces the generic "Ringing" one, under [notificationId].
     */
    fun start(context: Context, notificationId: Int = NOTIFICATION_ID, notification: Notification? = null) {
      stopRequested = false
      customNotification = notification?.let { notificationId to it }
      ContextCompat.startForegroundService(context, Intent(context, RingService::class.java))
    }

    fun stop() {
      val service = instance
      if (service == null) {
        stopRequested = true
      } else {
        service.stopForegroundAndSelf()
      }
    }
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    instance = this
    try {
      // startForegroundService() must always be followed by startForeground(), even when
      // the ringing already stopped.
      val (id, notification) = customNotification ?: (NOTIFICATION_ID to buildNotification())
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
      } else {
        startForeground(id, notification)
      }
    } catch (e: Exception) {
      Log.e(TAG, "Couldn't enter the foreground; ringing continues without it", e)
      stopSelf()
      return START_NOT_STICKY
    }
    if (stopRequested) {
      stopForegroundAndSelf()
    } else {
      Ringer.requestFocus(this)
    }
    return START_NOT_STICKY
  }

  override fun onDestroy() {
    instance = null
    super.onDestroy()
  }

  private fun stopForegroundAndSelf() {
    stopRequested = false
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
      stopForeground(STOP_FOREGROUND_REMOVE)
    } else {
      @Suppress("DEPRECATION")
      stopForeground(true)
    }
    stopSelf()
  }

  private fun buildNotification(): Notification {
    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      // Low importance: the alarm's own full-screen notification is the one the user sees.
      val channel = NotificationChannel(CHANNEL_ID, "Ringing", NotificationManager.IMPORTANCE_LOW)
      channel.setShowBadge(false)
      manager.createNotificationChannel(channel)
    }
    val icon =
      resources.getIdentifier("alarm_icon", "drawable", packageName).takeIf { it != 0 }
        ?: android.R.drawable.ic_lock_idle_alarm
    val launch =
      packageManager.getLaunchIntentForPackage(packageName)?.let {
        PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
      }
    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(icon)
      .setContentTitle("Ringing")
      .setContentIntent(launch)
      .setOngoing(true)
      .setSilent(true)
      .setCategory(NotificationCompat.CATEGORY_ALARM)
      .build()
  }
}
