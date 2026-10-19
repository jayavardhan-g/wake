// Copyright 2019 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.
package dev.fluttercommunity.plus.androidalarmmanager

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager

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
    val pendingResult = goAsync()
    AlarmService.handleAlarmNow(context.applicationContext, intent) { pendingResult.finish() }
  }

  companion object {
    private const val WAKE_LOCK_TIMEOUT_MS = 60_000L
  }
}