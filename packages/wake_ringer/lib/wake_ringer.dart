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

  static Future<void> stop() async {
    try {
      await _channel.invokeMethod('stop');
    } on MissingPluginException {
      // Nothing was started natively.
    }
  }
}
