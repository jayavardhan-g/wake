import 'dart:math';

import 'package:audio_session/audio_session.dart';
import 'package:clock_app/alarm/types/alarm.dart';
import 'package:clock_app/audio/logic/ringtones.dart';
import 'package:clock_app/audio/types/ringtone_manager.dart';
import 'package:clock_app/developer/logic/logger.dart';
import 'package:clock_app/timer/types/timer.dart';
import 'package:just_audio/just_audio.dart';
import 'package:vibration/vibration.dart';

Random random = Random();

class RingtonePlayer {
  static AudioPlayer? _alarmPlayer;
  static AudioPlayer? _timerPlayer;
  static AudioPlayer? _mediaPlayer;
  static AudioPlayer? activePlayer;
  static bool _vibratorIsAvailable = false;
  static bool _stopRisingVolume = false;

  // Bundled with the app, so it can always be played if the chosen melody
  // can't be (missing file, revoked permission, unsupported format).
  static const String _fallbackRingtoneUri =
      "asset:///assets/ringtones/default.mp3";

  static Future<void> initialize() async {
    _alarmPlayer ??= _createRingingPlayer();
    _timerPlayer ??= _createRingingPlayer();
    _mediaPlayer ??= AudioPlayer(handleInterruptions: true);
    _mediaPlayer?.setAndroidAudioAttributes(
      const AndroidAudioAttributes(
        contentType: AndroidAudioContentType.music,
        usage: AndroidAudioUsage.media,
      ),
    );
    _vibratorIsAvailable = (await Vibration.hasVibrator()) ?? false;
  }

  // Alarms and timers must make sound even when another app holds audio
  // focus, so playback doesn't wait on audio session activation (just_audio
  // silently skips play() if that fails) and the player keeps its own audio
  // attributes instead of inheriting the shared session's.
  static AudioPlayer _createRingingPlayer() => AudioPlayer(
        handleInterruptions: false,
        androidApplyAudioAttributes: false,
        handleAudioSessionActivation: false,
      );

  // Ask other apps to pause while ringing. The answer is only logged: the
  // alarm plays whether or not focus is granted.
  static Future<void> _requestAudioFocus(AndroidAudioUsage usage) async {
    try {
      final session = await AudioSession.instance;
      final granted = await session.setActive(
        true,
        androidAudioFocusGainType: AndroidAudioFocusGainType.gainTransient,
        androidAudioAttributes: AndroidAudioAttributes(
          usage: usage,
          contentType: AndroidAudioContentType.sonification,
        ),
      );
      if (!granted) {
        logger.w("Audio focus not granted, playing anyway");
      }
    } catch (e) {
      logger.e("Error requesting audio focus: $e");
    }
  }

  static Future<void> playUri(String ringtoneUri,
      {bool vibrate = false,
      LoopMode loopMode = LoopMode.one,
      AndroidAudioUsage channel = AndroidAudioUsage.alarm}) async {
    activePlayer = _mediaPlayer;
    await _play(ringtoneUri, vibrate: vibrate, loopMode: LoopMode.one);
  }


 
  static Future<void> playAlarm(Alarm alarm,
      {LoopMode loopMode = LoopMode.one}) async {
    await activePlayer?.stop();
    await _alarmPlayer?.dispose();
    _alarmPlayer = _createRingingPlayer();
    await _alarmPlayer?.setAndroidAudioAttributes(AndroidAudioAttributes(
      usage: alarm.audioChannel,
      contentType: AndroidAudioContentType.music,
    ));
    activePlayer = _alarmPlayer;
    await _requestAudioFocus(alarm.audioChannel);
    String uri = await getRingtoneUri(alarm.ringtone);

    logger.t("Playing alarm with uri: $uri");

    await _play(uri,
        vibrate: alarm.vibrate,
        loopMode: LoopMode.one,
        volume: alarm.volume / 100,
        secondsToMaxVolume: alarm.risingVolumeDuration.inSeconds,
        startAtRandomPos: alarm.shouldStartMelodyAtRandomPos,
        fallbackToDefault: true);
  }

  static Future<void> playTimer(ClockTimer timer,
      {LoopMode loopMode = LoopMode.one}) async {
    await _timerPlayer?.setAndroidAudioAttributes(AndroidAudioAttributes(
      usage: timer.audioChannel,
      contentType: AndroidAudioContentType.music,
    ));
    activePlayer = _timerPlayer;
    await _requestAudioFocus(timer.audioChannel);
    await _play(
      timer.ringtone.uri,
      vibrate: timer.vibrate,
      loopMode: LoopMode.one,
      volume: timer.volume / 100,
      secondsToMaxVolume: timer.risingVolumeDuration.inSeconds,
      fallbackToDefault: true,
    );
  }

  static Future<void> setVolume(double volume) async {
    logger.t("Setting volume to $volume");
    _stopRisingVolume = true;
    await activePlayer?.setVolume(volume);
  }

  static Future<void> _play(
    String ringtoneUri, {
    bool vibrate = false,
    LoopMode loopMode = LoopMode.one,
    double volume = 1.0,
    int secondsToMaxVolume = 0,
    bool startAtRandomPos = false,
    bool fallbackToDefault = false,
    // double duration = double.infinity,
  }) async {
    try {
      _stopRisingVolume = false;

      RingtoneManager.lastPlayedRingtoneUri = ringtoneUri;
      if (_vibratorIsAvailable && vibrate) {
        Vibration.vibrate(pattern: [500, 1000], repeat: 0);
      }
      // activePlayer?.
      await activePlayer?.stop();
      await activePlayer?.setLoopMode(loopMode);
      Duration? duration = await activePlayer
          ?.setAudioSource(AudioSource.uri(Uri.parse(ringtoneUri)));
      logger.t("Duration: $duration");

      if (duration != null && startAtRandomPos) {
        double randomNumber = random.nextInt(100) / 100.0;
        logger.t("Starting at random position: $randomNumber");
        activePlayer?.seek(duration * randomNumber);
      }
      await setVolume(volume);

      // Gradually increase the volume
      if (secondsToMaxVolume > 0) {
        for (int i = 0; i <= 10; i++) {
          Future.delayed(
            Duration(milliseconds: i * (secondsToMaxVolume * 100)),
            () {
              if (!_stopRisingVolume) {
                setVolume((i / 10) * volume);
              }
            },
          );
        }
      }
      // Future.delayed(
      //   Duration(seconds: duration.toInt()),
      //   () async {
      //     await stop();
      //   },
      // );

      // Don't use await here as this will only return after the audio is done
      activePlayer?.play();

      if (fallbackToDefault && ringtoneUri != _fallbackRingtoneUri) {
        _verifyPlaybackStarted(activePlayer, ringtoneUri, vibrate: vibrate,
            loopMode: loopMode, volume: volume,
            secondsToMaxVolume: secondsToMaxVolume);
      }
    } catch (e) {
      logger.e("Error playing $ringtoneUri: $e");
      if (fallbackToDefault && ringtoneUri != _fallbackRingtoneUri) {
        logger.w("Falling back to the default ringtone");
        await _play(_fallbackRingtoneUri,
            vibrate: vibrate,
            loopMode: loopMode,
            volume: volume,
            secondsToMaxVolume: secondsToMaxVolume);
      }
    }
  }

  // Vibration starts before the audio, so a melody that loads but never
  // plays leaves only vibration. If the player isn't playing a few seconds
  // after starting, switch to the bundled ringtone.
  static void _verifyPlaybackStarted(
    AudioPlayer? player,
    String ringtoneUri, {
    required bool vibrate,
    required LoopMode loopMode,
    required double volume,
    required int secondsToMaxVolume,
  }) {
    Future.delayed(const Duration(seconds: 5), () async {
      // Stopped or replaced in the meantime (dismissed, snoozed, new alarm)
      if (player == null ||
          activePlayer != player ||
          RingtoneManager.lastPlayedRingtoneUri != ringtoneUri) {
        return;
      }
      final state = player.processingState;
      if (player.playing && state == ProcessingState.ready) return;
      logger.w(
          "Ringtone $ringtoneUri not playing after 5s (playing: ${player.playing}, state: $state), falling back to the default ringtone");
      await _play(_fallbackRingtoneUri,
          vibrate: false,
          loopMode: loopMode,
          volume: volume,
          secondsToMaxVolume: secondsToMaxVolume);
    });
  }

  static Future<void> pause() async {
    await activePlayer?.pause();
    if (_vibratorIsAvailable) {
      await Vibration.cancel();
    }
  }

  static Future<void> stop() async {
    await activePlayer?.stop();
    final session = await AudioSession.instance;
    await session.setActive(false);
    if (_vibratorIsAvailable) {
      await Vibration.cancel();
    }
    RingtoneManager.lastPlayedRingtoneUri = "";
    _stopRisingVolume = false;
  }
}
