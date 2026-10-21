import 'dart:io';

import 'package:clock_app/alarm/types/alarm.dart';
import 'package:clock_app/audio/logic/ringtones.dart';
import 'package:clock_app/developer/logic/logger.dart';
import 'package:wake_ringer/wake_ringer.dart';

// Alarms that fire before the first unlock after a reboot are rung natively
// (packages/wake_ringer), because this app's data can't be read until then.
// These keep the native side's copy of each alarm's ring settings current.

Future<void> saveLockedAlarmConfig(Alarm alarm) async {
  if (Platform.environment.containsKey('FLUTTER_TEST')) return;
  try {
    final ringtoneUri = await getRingtoneUri(alarm.ringtone);
    for (final runner in alarm.activeSchedule.alarmRunners) {
      await WakeRinger.saveLockedAlarm(
        id: runner.id,
        ringtoneUri: ringtoneUri,
        usage: alarm.audioChannel.value,
        volume: alarm.volume / 100,
        secondsToMaxVolume: alarm.risingVolumeDuration.inSeconds,
        vibrate: alarm.vibrate,
        ringMinutes: alarm.ringDuration.inMinutes,
        snoozeEnabled: alarm.snoozeEnabled,
        snoozeMinutes: alarm.snoozeLength.floor(),
        maxSnoozes: alarm.maxSnoozes,
      );
    }
  } catch (e) {
    logger.e("Error saving locked alarm config for alarm ${alarm.id}: $e");
  }
}

Future<void> removeLockedAlarmConfig(int scheduleId) =>
    WakeRinger.removeLockedAlarm(scheduleId);

Future<void> pruneLockedAlarmRingtones() => WakeRinger.pruneLockedRingtones();
