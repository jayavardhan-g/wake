// Copyright 2019 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.
package dev.fluttercommunity.plus.androidalarmmanager

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.UserManager
import org.json.JSONException
import org.json.JSONObject

class AlarmBroadcastReceiver : BroadcastReceiver() {
  /**
   * Invoked by the OS when a timer goes off.
   *
   * The associated timer was registered in [AlarmService].
   *
   * In Android, timer notifications require a [BroadcastReceiver] as the artifact that is
   * notified when the timer goes off. As a result, this method is kept simple, immediately
   * offloading any work to [AlarmService.enqueueAlarmProcessing].
   *
   * This method is the beginning of an execution path that will eventually execute a desired
   * Dart callback function, as registered by the Dart side of the android_alarm_manager plugin.
   * However, there may be asynchronous gaps between [onReceive] and the eventual invocation
   * of the Dart callback because [AlarmService] may need to spin up a Flutter execution
   * context before the callback can be invoked.
   */
  override fun onReceive(context: Context, intent: Intent) {
    // Run the alarm now rather than through JobIntentService: that goes via
    // JobScheduler, which defers jobs while the device is idle (Doze), so an
    // alarm on a locked phone only rang once it was unlocked.
    //
    // The wake lock covers starting the Flutter engine and the ringtone; it
    // times out on its own so it can't keep the device out of deep sleep.
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    powerManager
      .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wake:alarm_start")
      .acquire(WAKE_LOCK_TIMEOUT_MS)
    if (!isUserUnlocked(context)) {
      handOffLockedAlarm(context, intent)
      return
    }
    val pendingResult = goAsync()
    AlarmService.handleAlarmNow(context.applicationContext, intent) { pendingResult.finish() }
  }

  // Before the first unlock after a reboot the Flutter app can't run (its data is in
  // credential-protected storage), so the alarm is rung natively by Wake's
  // LockedAlarmReceiver instead. It is removed from the persistent alarms first so the
  // reschedule at BOOT_COMPLETED doesn't ring it a second time through Flutter.
  private fun handOffLockedAlarm(context: Context, intent: Intent) {
    val id = intent.getIntExtra("id", -1)
    AlarmService.clearPersistentAlarm(context, id)
    val params =
      try {
        JSONObject(intent.getStringExtra("params") ?: "{}")
      } catch (e: JSONException) {
        JSONObject()
      }
    val locked =
      Intent(LOCKED_ALARM_ACTION)
        .setClassName(context.packageName, LOCKED_ALARM_RECEIVER)
        .putExtra("id", id)
        .putExtra("type", params.optString("type"))
        .putExtra("timeOfDay", params.optString("timeOfDay"))
    context.sendBroadcast(locked)
  }

  private fun isUserUnlocked(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.N ||
      context.getSystemService(UserManager::class.java).isUserUnlocked

  companion object {
    private const val WAKE_LOCK_TIMEOUT_MS = 60_000L
    private const val LOCKED_ALARM_ACTION = "com.jayavardhan.wake.ringer.RING"
    private const val LOCKED_ALARM_RECEIVER = "com.jayavardhan.wake.ringer.LockedAlarmReceiver"
  }
}