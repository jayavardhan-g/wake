package com.jayavardhan.wake.ringer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restores snoozes set from a locked alarm's notification after a reboot (see
 * [LockedAlarmReceiver]). Kept separate from LockedAlarmReceiver because receiving boot
 * broadcasts requires an exported receiver, and the alarm actions (ring, snooze, dismiss) must
 * stay unreachable from other apps; this one only accepts the system's boot broadcasts and
 * forwards them internally.
 */
class LockedBootReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    when (intent.action) {
      Intent.ACTION_LOCKED_BOOT_COMPLETED, Intent.ACTION_BOOT_COMPLETED -> {
        context.sendBroadcast(
          Intent(LockedAlarmReceiver.ACTION_RESTORE).setClass(context, LockedAlarmReceiver::class.java),
        )
      }
    }
  }
}
