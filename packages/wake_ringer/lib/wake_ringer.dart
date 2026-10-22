import 'package:flutter/services.dart';

/// Rings alarms and timers natively, from a foreground service, instead of
/// through just_audio. Android keeps a ringing foreground service's process
/// alive and grants it audio focus, which background apps no longer get.
class WakeRinger {
  static const MethodChannel _channel =
      MethodChannel('com.jayavardhan.wake/ringer');

  /// Starts ringing [uri] on the Android audio [usage] (an `AudioAttributes`
  /// usage constant). Falls back to the system alarm sound if [uri] can't be
  /// played. Returns false if nothing could be played, or on platforms
  /// without the native ringer.
  static Future<bool> start({
    required String uri,
    required int usage,
    double volume = 1.0,
    int secondsToMaxVolume = 0,
    bool vibrate = false,
    bool startAtRandomPosition = false,
  }) async {
    try {
      return await _channel.invokeMethod<bool>('start', {
            'uri': uri,
            'usage': usage,
            'volume': volume,
            'secondsToMaxVolume': secondsToMaxVolume,
            'vibrate': vibrate,
            'startAtRandomPosition': startAtRandomPosition,
          }) ??
          false;
    } on MissingPluginException {
      return false;
    } on PlatformException {
      return false;
    }
  }

  static Future<void> setVolume(double volume) async {
    await _channel.invokeMethod('setVolume', {'volume': volume});
  }

  /// Scales the ringing volume by [factor] (0 to 1) without stopping a
  /// rising-volume ramp, e.g. quieter while the user solves tasks.
  static Future<void> setVolumeFactor(double factor) async {
    await _channel.invokeMethod('setVolumeFactor', {'factor': factor});
  }

  static Future<void> stop() async {
    try {
      await _channel.invokeMethod('stop');
    } on MissingPluginException {
      // Nothing was started natively.
    }
  }

  /// Saves how to ring the alarm schedule [id] if it fires before the first
  /// unlock after a reboot, when this app can't run. Stored in
  /// device-protected storage with a copy of [ringtoneUri], so it takes no
  /// label on purpose; [minuteOfDay] (the alarm's set time) identifies the
  /// alarm in its notifications instead. [usage] is an Android
  /// `AudioAttributes` usage.
  static Future<void> saveLockedAlarm({
    required int minuteOfDay,
    required int id,
    required String ringtoneUri,
    required int usage,
    required double volume,
    required int secondsToMaxVolume,
    required bool vibrate,
    required int ringMinutes,
    required bool snoozeEnabled,
    required int snoozeMinutes,
    required int maxSnoozes,
  }) async {
    await _invoke('saveLockedAlarm', {
      'id': id,
      'ringtoneUri': ringtoneUri,
      'usage': usage,
      'volume': volume,
      'secondsToMaxVolume': secondsToMaxVolume,
      'vibrate': vibrate,
      'ringMinutes': ringMinutes,
      'snoozeEnabled': snoozeEnabled,
      'snoozeMinutes': snoozeMinutes,
      'maxSnoozes': maxSnoozes,
      'minuteOfDay': minuteOfDay,
    });
  }

  static Future<void> removeLockedAlarm(int id) async {
    await _invoke('removeLockedAlarm', {'id': id});
  }

  /// Deletes melody copies no saved locked alarm uses any more.
  static Future<void> pruneLockedRingtones() async {
    await _invoke('pruneLockedRingtones');
  }

  // Locked-alarm bookkeeping must never break scheduling, e.g. in tests where
  // the plugin isn't registered.
  static Future<void> _invoke(String method, [Object? arguments]) async {
    try {
      await _channel.invokeMethod(method, arguments);
    } on MissingPluginException {
      // No native side.
    } on PlatformException {
      // Logged natively.
    }
  }
}
