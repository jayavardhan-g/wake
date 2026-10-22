package com.jayavardhan.wake.ringer

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.text.format.DateFormat
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.Calendar
import java.util.Date

/**
 * Rings alarms that fire before the first unlock after a reboot, when the Flutter app can't run.
 * android_alarm_manager_plus's AlarmBroadcastReceiver hands them over with [ACTION_RING]. Snooze,
 * dismiss and auto-snooze after the ring duration are handled here as well, without Flutter.
 *
 * Settings come from [LockedAlarms]. Notifications identify the alarm by its set time: labels
 * aren't kept in device-protected storage.
 */
class LockedAlarmReceiver : BroadcastReceiver() {
  companion object {
    private const val TAG = "WakeLockedAlarm"
    const val ACTION_RING = "com.jayavardhan.wake.ringer.RING"
    private const val ACTION_SNOOZE = "com.jayavardhan.wake.ringer.SNOOZE"
    private const val ACTION_DISMISS = "com.jayavardhan.wake.ringer.DISMISS"
    private const val ACTION_TIMEOUT = "com.jayavardhan.wake.ringer.TIMEOUT"
    private const val ACTION_REPOST = "com.jayavardhan.wake.ringer.REPOST"
    // Sent by LockedBootReceiver after a reboot.
    const val ACTION_RESTORE = "com.jayavardhan.wake.ringer.RESTORE"
    private const val CHANNEL_ID = "wake_locked_alarms"
    private const val NOTIFICATION_TAG = "locked_alarm"
    // A handed-over alarm this late (e.g. the phone was off) is reported as missed instead.
    private const val MAX_LATE_MINUTES = 60

    // The alarm ringing in this process, the config it rings with and whether it's a snooze.
    // Flutter replaces or removes saved configs whenever it reschedules its alarms (e.g. right
    // after unlock), which mustn't change an alarm that's already ringing.
    private var ringingId: Int? = null
    private var ringingConfig: LockedAlarms.Config? = null
    private var ringingIsSnooze = false
  }

  override fun onReceive(context: Context, intent: Intent) {
    val id = intent.getIntExtra("id", -1)
    val snoozes = intent.getIntExtra("snoozes", 0)
    when (intent.action) {
      ACTION_RING -> ring(context, id, snoozes, intent.getStringExtra("timeOfDay"))
      ACTION_SNOOZE -> snooze(context, id, snoozes)
      ACTION_DISMISS -> dismiss(context, id)
      ACTION_TIMEOUT -> timeOut(context, id, snoozes)
      ACTION_RESTORE -> restoreSnoozes(context)
      ACTION_REPOST ->
        if (ringingId == id) {
          notificationManager(context).notify(notificationId(id), ringingNotification(context, id, snoozes))
        }
    }
  }

  private fun ring(context: Context, id: Int, snoozes: Int, timeOfDay: String?) {
    // A snooze from a locked alarm rings with the config it was snoozed with, and no longer
    // needs restoring after a reboot.
    val config = LockedAlarms.snooze(context, id)?.config ?: LockedAlarms.get(context, id)
    LockedAlarms.removeSnooze(context, id)
    val late = minutesLate(timeOfDay)
    if (late > MAX_LATE_MINUTES) {
      Log.i(TAG, "Alarm $id is $late minutes late, reporting it as missed")
      postMissed(context, config, id, System.currentTimeMillis() - late * 60_000L)
      return
    }
    // One alarm rings at a time: a newer one replaces it.
    ringingId?.let { if (it != id) stopRinging(context, it) }

    val ringing =
      Ringer.start(
        context,
        config.ringtonePath,
        config.usage,
        config.volume,
        config.secondsToMaxVolume,
        config.vibrate,
        startAtRandomPosition = false,
      )
    if (!ringing) Log.e(TAG, "Nothing could be played for alarm $id")
    ringingId = id
    ringingConfig = config
    // A snooze set in Flutter is handed over like any alarm, at a time other than the alarm's.
    ringingIsSnooze = snoozes > 0 || isOtherThanAlarmTime(timeOfDay, config)
    LockedAlarms.ringingRingtonePath = config.ringtonePath
    Log.i(TAG, "Ringing alarm $id before unlock (snoozed $snoozes times)")

    val notification = ringingNotification(context, id, snoozes)
    try {
      RingService.start(context, notificationId(id), notification)
    } catch (e: Exception) {
      Log.e(TAG, "Couldn't start the ringing service, posting the notification directly", e)
      notificationManager(context).notify(notificationId(id), notification)
    }
    exactAlarm(context, System.currentTimeMillis() + config.ringMinutes * 60_000L, pending(context, ACTION_TIMEOUT, id, snoozes))
  }

  private fun snooze(context: Context, id: Int, snoozes: Int) {
    val config = configFor(context, id)
    val at = System.currentTimeMillis() + config.snoozeMinutes * 60_000L
    // Saved before ringing stops, so the melody copy is always referenced.
    LockedAlarms.saveSnooze(context, LockedAlarms.Snooze(id, at, snoozes + 1, config))
    stopRinging(context, id)
    alarmClock(context, at, pending(context, ACTION_RING, id, snoozes + 1))
    postSnoozed(context, config, id, at)
    Log.i(TAG, "Snoozed alarm $id until $at")
  }

  // A reboot clears AlarmManager alarms and notifications: reschedule pending snoozes, ring
  // ones that came due while the phone was restarting, and report older ones as missed.
  // Runs at both LOCKED_BOOT_COMPLETED and BOOT_COMPLETED; ringing removes the saved snooze,
  // so one that's due rings only once.
  private fun restoreSnoozes(context: Context) {
    val now = System.currentTimeMillis()
    for (snooze in LockedAlarms.snoozes(context)) {
      when {
        snooze.at > now -> {
          alarmClock(context, snooze.at, pending(context, ACTION_RING, snooze.id, snooze.snoozes))
          postSnoozed(context, snooze.config, snooze.id, snooze.at)
        }
        now - snooze.at <= MAX_LATE_MINUTES * 60_000L -> ring(context, snooze.id, snooze.snoozes, null)
        else -> {
          LockedAlarms.removeSnooze(context, snooze.id)
          postMissed(context, snooze.config, snooze.id, snooze.at)
        }
      }
    }
  }

  private fun postSnoozed(context: Context, config: LockedAlarms.Config, id: Int, at: Long) {
    val until = DateFormat.getTimeFormat(context).format(Date(at))
    notificationManager(context).notify(
      NOTIFICATION_TAG,
      notificationId(id),
      baseNotification(
        context,
        "Alarm snoozed",
        alarmTime(context, config)?.let { "$it alarm, until $until" } ?: "Until $until",
      )
        .addAction(0, "Dismiss", pending(context, ACTION_DISMISS, id))
        .build(),
    )
  }

  private fun dismiss(context: Context, id: Int) {
    LockedAlarms.removeSnooze(context, id)
    stopRinging(context, id)
    // Cancels a pending snooze too.
    alarmManager(context).cancel(pending(context, ACTION_RING, id))
    notificationManager(context).cancel(NOTIFICATION_TAG, notificationId(id))
    Log.i(TAG, "Dismissed alarm $id")
  }

  private fun timeOut(context: Context, id: Int, snoozes: Int) {
    if (ringingId != id) return
    val config = configFor(context, id)
    if (config.snoozeEnabled && snoozes < config.maxSnoozes) {
      snooze(context, id, snoozes)
    } else {
      stopRinging(context, id)
      postMissed(context, config, id)
    }
  }

  private fun stopRinging(context: Context, id: Int) {
    alarmManager(context).cancel(pending(context, ACTION_TIMEOUT, id))
    if (ringingId == id) {
      Ringer.stop(context)
      RingService.stop()
      notificationManager(context).cancel(notificationId(id))
      ringingId = null
      ringingConfig = null
      LockedAlarms.ringingRingtonePath = null
    }
  }

  private fun configFor(context: Context, id: Int): LockedAlarms.Config =
    ringingConfig?.takeIf { id == ringingId } ?: LockedAlarms.get(context, id)

  // [dueAt]: when the alarm should have rung, shown if the alarm's own time isn't known.
  private fun postMissed(
    context: Context,
    config: LockedAlarms.Config,
    id: Int,
    dueAt: Long = System.currentTimeMillis(),
  ) {
    val time = alarmTime(context, config) ?: DateFormat.getTimeFormat(context).format(Date(dueAt))
    notificationManager(context).notify(
      NOTIFICATION_TAG,
      notificationId(id),
      baseNotification(context, "Missed alarm", time).setAutoCancel(true).build(),
    )
  }

  private fun ringingNotification(context: Context, id: Int, snoozes: Int): Notification {
    val config = configFor(context, id)
    val builder =
      baseNotification(
        context,
        if (ringingIsSnooze || snoozes > 0) "Alarm (snoozed)" else "Alarm",
        alarmTime(context, config) ?: DateFormat.getTimeFormat(context).format(Date()),
      )
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_MAX)
        // Swiping it away while ringing would leave no way to stop the alarm: post it again.
        .setDeleteIntent(pending(context, ACTION_REPOST, id, snoozes))
    if (config.snoozeEnabled && snoozes < config.maxSnoozes) {
      builder.addAction(0, "Snooze", pending(context, ACTION_SNOOZE, id, snoozes))
    }
    return builder.addAction(0, "Dismiss", pending(context, ACTION_DISMISS, id)).build()
  }

  private fun baseNotification(context: Context, title: String, text: String): NotificationCompat.Builder {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      // The ringtone and vibration come from Ringer, so the channel itself is silent.
      val channel = NotificationChannel(CHANNEL_ID, "Alarms before unlock", NotificationManager.IMPORTANCE_HIGH)
      channel.setSound(null, null)
      channel.enableVibration(false)
      channel.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
      notificationManager(context).createNotificationChannel(channel)
    }
    val icon =
      context.resources.getIdentifier("alarm_icon", "drawable", context.packageName).takeIf { it != 0 }
        ?: android.R.drawable.ic_lock_idle_alarm
    // Tapping opens Wake (after unlocking, if needed). Its alarm screen doesn't know about
    // alarms rung here, so they're snoozed or dismissed from these notifications.
    val openApp =
      context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
        PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
      }
    return NotificationCompat.Builder(context, CHANNEL_ID)
      .setSmallIcon(icon)
      .setContentTitle(title)
      .setContentText(text)
      .setContentIntent(openApp)
      .setCategory(NotificationCompat.CATEGORY_ALARM)
      .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
  }

  // Whether the handed-over ring time ([timeOfDay], fractional hours) isn't the alarm's own set
  // time, i.e. it's a snooze.
  private fun isOtherThanAlarmTime(timeOfDay: String?, config: LockedAlarms.Config): Boolean {
    val hours = timeOfDay?.toDoubleOrNull() ?: return false
    return config.minuteOfDay >= 0 && Math.round(hours * 60).toInt() % 1440 != config.minuteOfDay
  }

  // The alarm's set time in the user's time format, which identifies it since labels aren't
  // kept; null if it wasn't saved.
  private fun alarmTime(context: Context, config: LockedAlarms.Config): String? {
    val minuteOfDay = config.minuteOfDay
    if (minuteOfDay < 0) return null
    val time =
      Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, minuteOfDay / 60)
        set(Calendar.MINUTE, minuteOfDay % 60)
      }
    return DateFormat.getTimeFormat(context).format(time.time)
  }

  // Alarm schedule ids are large random ints, also used by Wake's other notifications as ids.
  // Setting the top bits keeps these from replacing them.
  private fun notificationId(id: Int) = id or 0x40000000

  // The data URI makes each (action, alarm) pair a distinct PendingIntent.
  private fun pending(context: Context, action: String, id: Int, snoozes: Int = 0): PendingIntent {
    val intent =
      Intent(context, LockedAlarmReceiver::class.java)
        .setAction(action)
        .setData(Uri.parse("wake-locked-alarm://$id"))
        .putExtra("id", id)
        .putExtra("snoozes", snoozes)
    return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
  }

  // Wake holds USE_EXACT_ALARM, but if exact alarms were ever refused, a crash here would stop
  // a ringing alarm: fall back to an inexact one instead.
  private fun exactAlarm(context: Context, at: Long, operation: PendingIntent) {
    val manager = alarmManager(context)
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation)
      } else {
        manager.setExact(AlarmManager.RTC_WAKEUP, at, operation)
      }
    } catch (e: SecurityException) {
      Log.e(TAG, "Exact alarms not allowed, scheduling an inexact one", e)
      manager.set(AlarmManager.RTC_WAKEUP, at, operation)
    }
  }

  private fun alarmClock(context: Context, at: Long, operation: PendingIntent) {
    try {
      alarmManager(context).setAlarmClock(AlarmManager.AlarmClockInfo(at, null), operation)
    } catch (e: SecurityException) {
      Log.e(TAG, "Exact alarms not allowed, scheduling the snooze as an inexact one", e)
      alarmManager(context).set(AlarmManager.RTC_WAKEUP, at, operation)
    }
  }

  // timeOfDay is the alarm's time as fractional hours (Flutter's TimeOfDay encoding). Rings
  // within a few minutes before it (clock adjustments) don't count as late.
  private fun minutesLate(timeOfDay: String?): Int {
    val hours = timeOfDay?.toDoubleOrNull() ?: return 0
    val now = Calendar.getInstance()
    val late = (now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE) - Math.round(hours * 60).toInt() + 1440) % 1440
    return if (late > 1440 - 5) 0 else late
  }

  private fun alarmManager(context: Context) = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

  private fun notificationManager(context: Context) =
    context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
}
