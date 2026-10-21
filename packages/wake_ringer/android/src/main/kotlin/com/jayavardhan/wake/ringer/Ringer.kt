package com.jayavardhan.wake.ringer

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import java.io.File
import kotlin.random.Random

/**
 * Plays the ringing alarm or timer: a looping [MediaPlayer] on the requested audio usage,
 * transient audio focus so other apps pause, optional rising volume and vibration.
 *
 * Process-wide, so any Flutter engine (the alarm isolate or the UI) can stop it. All calls
 * must happen on the main thread.
 */
internal object Ringer {
  private const val TAG = "WakeRinger"
  private const val RAMP_STEP_MS = 1000L
  private const val BUNDLED_RINGTONE = "flutter_assets/assets/ringtones/default.mp3"

  private val handler = Handler(Looper.getMainLooper())
  private var player: MediaPlayer? = null
  private var vibrator: Vibrator? = null
  private var focusRequest: AudioFocusRequest? = null
  private var attributes: AudioAttributes? = null
  private var targetVolume = 1f

  /** Starts ringing. Returns false if neither [uri] nor the system alarm sound would play. */
  fun start(
    context: Context,
    uri: String,
    usage: Int,
    volume: Float,
    secondsToMaxVolume: Int,
    vibrate: Boolean,
    startAtRandomPosition: Boolean,
  ): Boolean {
    stop(context)

    val audioAttributes =
      AudioAttributes.Builder()
        .setUsage(usage)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    val mediaPlayer =
      prepare(context, audioAttributes, uri) { it.setDataSource(context, parseUri(uri)) }
        ?: prepare(context, audioAttributes, "system alarm sound") {
          it.setDataSource(context, Settings.System.DEFAULT_ALARM_ALERT_URI)
        }
        // Inside the APK, so readable even before the first unlock after a reboot.
        ?: prepare(context, audioAttributes, BUNDLED_RINGTONE) { player ->
          context.assets.openFd(BUNDLED_RINGTONE).use {
            player.setDataSource(it.fileDescriptor, it.startOffset, it.length)
          }
        }
        ?: return false

    attributes = audioAttributes
    requestFocus(context)
    targetVolume = volume
    if (startAtRandomPosition && mediaPlayer.duration > 0) {
      mediaPlayer.seekTo(Random.nextInt(mediaPlayer.duration))
    }
    if (secondsToMaxVolume > 0) {
      mediaPlayer.setVolume(0f, 0f)
      rampVolume(System.currentTimeMillis(), secondsToMaxVolume * 1000L)
    } else {
      mediaPlayer.setVolume(volume, volume)
    }
    mediaPlayer.start()
    player = mediaPlayer

    if (vibrate) startVibration(context, usage)
    Log.i(TAG, "Ringing $uri (usage $usage, volume $volume, rising ${secondsToMaxVolume}s)")
    return true
  }

  /** Sets the volume now and stops any volume ramp, e.g. to quieten the alarm during tasks. */
  fun setVolume(volume: Float) {
    handler.removeCallbacksAndMessages(null)
    targetVolume = volume
    player?.setVolume(volume, volume)
  }

  fun stop(context: Context) {
    handler.removeCallbacksAndMessages(null)
    player?.let {
      try {
        it.stop()
      } catch (e: IllegalStateException) {
        Log.w(TAG, "Player was not playing", e)
      }
      it.release()
    }
    player = null
    vibrator?.cancel()
    vibrator = null
    abandonFocus(context)
  }

  // Ringtones are plain file paths for melodies copied into the app, otherwise URIs
  // (content:// system sounds, file:// files picked from a folder).
  private fun parseUri(uri: String): Uri =
    if (uri.startsWith("/")) Uri.fromFile(File(uri)) else Uri.parse(uri)

  private fun prepare(
    context: Context,
    attributes: AudioAttributes,
    source: String,
    setDataSource: (MediaPlayer) -> Unit,
  ): MediaPlayer? {
    val mediaPlayer = MediaPlayer()
    return try {
      mediaPlayer.setAudioAttributes(attributes)
      setDataSource(mediaPlayer)
      mediaPlayer.isLooping = true
      // Keeps the CPU awake only while the player is actually playing.
      mediaPlayer.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
      mediaPlayer.prepare()
      mediaPlayer
    } catch (e: Exception) {
      Log.e(TAG, "Can't play $source", e)
      mediaPlayer.release()
      null
    }
  }

  private fun rampVolume(startedAt: Long, durationMs: Long) {
    val fraction = ((System.currentTimeMillis() - startedAt).toFloat() / durationMs).coerceIn(0f, 1f)
    val volume = targetVolume * fraction
    player?.setVolume(volume, volume)
    if (fraction < 1f) handler.postDelayed({ rampVolume(startedAt, durationMs) }, RAMP_STEP_MS)
  }

  // Focus is requested so music and video pause while the alarm rings. Losing it is ignored:
  // the alarm keeps ringing until it is dismissed or snoozed. Android ignores requests from
  // background apps, so RingService asks again once it is in the foreground.
  fun requestFocus(context: Context) {
    // Cleared by stop(), so this does nothing once ringing has stopped.
    val attributes = attributes ?: return
    val audioManager = audioManager(context)
    val result =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val request =
          focusRequest
            ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
              .setAudioAttributes(attributes)
              .setOnAudioFocusChangeListener {}
              .build()
        focusRequest = request
        audioManager.requestAudioFocus(request)
      } else {
        @Suppress("DEPRECATION")
        audioManager.requestAudioFocus(null, AudioManager.STREAM_ALARM, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
      }
    if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
      Log.w(TAG, "Audio focus not granted ($result), ringing anyway")
    }
  }

  // Android identifies a focus holder by the AudioManager instance that asked, and each Context
  // (the service, the application) has its own. Using the application's for both requesting
  // and abandoning is what lets abandoning actually release focus.
  private fun audioManager(context: Context): AudioManager =
    context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

  private fun abandonFocus(context: Context) {
    val audioManager = audioManager(context)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
    } else {
      @Suppress("DEPRECATION")
      audioManager.abandonAudioFocus(null)
    }
    focusRequest = null
    attributes = null
  }

  private fun startVibration(context: Context, usage: Int) {
    val vib =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
      } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
      }
    if (!vib.hasVibrator()) return
    val pattern = longArrayOf(0, 500, 1000)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      vib.vibrate(
        VibrationEffect.createWaveform(pattern, 1),
        VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM),
      )
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      @Suppress("DEPRECATION")
      vib.vibrate(VibrationEffect.createWaveform(pattern, 1), AudioAttributes.Builder().setUsage(usage).build())
    } else {
      @Suppress("DEPRECATION")
      vib.vibrate(pattern, 1)
    }
    vibrator = vib
  }
}
