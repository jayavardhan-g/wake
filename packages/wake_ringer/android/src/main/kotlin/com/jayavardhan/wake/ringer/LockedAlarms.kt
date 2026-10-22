package com.jayavardhan.wake.ringer

import android.content.Context
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import org.json.JSONException
import org.json.JSONObject

/**
 * What Wake needs to ring an alarm before the first unlock after a reboot, when the Flutter app
 * can't run: how to ring each alarm schedule id, and a copy of its melody. Both live in
 * device-protected storage, which is readable before unlock. The originals are in
 * credential-protected storage, which isn't.
 *
 * Labels are deliberately not stored: device-protected storage is readable without the user's
 * credentials, so it only gets what ringing needs.
 */
internal object LockedAlarms {
  private const val TAG = "WakeLockedAlarms"
  private const val PREFERENCES = "wake_locked_alarms"
  private const val SNOOZE_PREFERENCES = "wake_locked_snoozes"
  private const val RINGTONE_DIRECTORY = "locked_ringtones"

  data class Config(
    // Empty for the system or bundled default sound.
    val ringtonePath: String,
    val usage: Int,
    val volume: Float,
    val secondsToMaxVolume: Int,
    val vibrate: Boolean,
    val ringMinutes: Int,
    val snoozeEnabled: Boolean,
    val snoozeMinutes: Int,
    val maxSnoozes: Int,
    // The alarm's set time, shown in its notifications instead of a label; -1 if unknown.
    val minuteOfDay: Int,
  )

  // Used when an alarm has no saved config, e.g. one scheduled before this feature existed.
  val DEFAULT = Config("", AudioAttributes.USAGE_ALARM, 1f, 0, true, 5, true, 5, 3, -1)

  /** Saves [config] for alarm [id], copying [ringtoneUri]. Slow (file copy): not on the main thread. */
  fun save(context: Context, id: Int, ringtoneUri: String, config: Config) {
    val path = copyRingtone(context, ringtoneUri) ?: ""
    val json =
      JSONObject()
        .put("ringtonePath", path)
        .put("usage", config.usage)
        .put("volume", config.volume.toDouble())
        .put("secondsToMaxVolume", config.secondsToMaxVolume)
        .put("vibrate", config.vibrate)
        .put("ringMinutes", config.ringMinutes)
        .put("snoozeEnabled", config.snoozeEnabled)
        .put("snoozeMinutes", config.snoozeMinutes)
        .put("maxSnoozes", config.maxSnoozes)
        .put("minuteOfDay", config.minuteOfDay)
    preferences(context).edit().putString(id.toString(), json.toString()).apply()
  }

  fun remove(context: Context, id: Int) {
    preferences(context).edit().remove(id.toString()).apply()
  }

  fun get(context: Context, id: Int): Config {
    val stored = preferences(context).getString(id.toString(), null) ?: return DEFAULT
    return try {
      val json = JSONObject(stored)
      Config(
        ringtonePath = json.optString("ringtonePath"),
        usage = json.optInt("usage", DEFAULT.usage),
        volume = json.optDouble("volume", 1.0).toFloat(),
        secondsToMaxVolume = json.optInt("secondsToMaxVolume"),
        vibrate = json.optBoolean("vibrate", DEFAULT.vibrate),
        ringMinutes = json.optInt("ringMinutes", DEFAULT.ringMinutes).coerceAtLeast(1),
        snoozeEnabled = json.optBoolean("snoozeEnabled", DEFAULT.snoozeEnabled),
        snoozeMinutes = json.optInt("snoozeMinutes", DEFAULT.snoozeMinutes).coerceAtLeast(1),
        maxSnoozes = json.optInt("maxSnoozes", DEFAULT.maxSnoozes),
        minuteOfDay = json.optInt("minuteOfDay", -1),
      )
    } catch (e: JSONException) {
      Log.e(TAG, "Invalid config for alarm $id", e)
      DEFAULT
    }
  }

  /** Deletes melody copies that no saved alarm uses any more. */
  fun pruneRingtones(context: Context) {
    val used =
      preferences(context).all.values.mapNotNullTo(HashSet<String>()) { value ->
        try {
          JSONObject(value as? String ?: return@mapNotNullTo null).optString("ringtonePath")
        } catch (e: JSONException) {
          null
        }
      }
    ringtoneDirectory(context).listFiles()?.forEach { file ->
      if (file.absolutePath !in used) file.delete()
    }
  }

  data class Snooze(val id: Int, val at: Long, val snoozes: Int)

  // Snoozes set from a locked alarm's notification are AlarmManager alarms, which a reboot
  // clears; they are kept here so LockedBootReceiver can restore them.
  fun saveSnooze(context: Context, snooze: Snooze) {
    snoozePreferences(context)
      .edit()
      .putString(snooze.id.toString(), JSONObject().put("at", snooze.at).put("snoozes", snooze.snoozes).toString())
      .apply()
  }

  fun removeSnooze(context: Context, id: Int) {
    snoozePreferences(context).edit().remove(id.toString()).apply()
  }

  fun snoozes(context: Context): List<Snooze> =
    snoozePreferences(context).all.mapNotNull { (key, value) ->
      try {
        val json = JSONObject(value as? String ?: return@mapNotNull null)
        Snooze(key.toInt(), json.getLong("at"), json.optInt("snoozes"))
      } catch (e: Exception) {
        Log.e(TAG, "Invalid snooze $key", e)
        null
      }
    }

  private fun snoozePreferences(context: Context) =
    storageContext(context).getSharedPreferences(SNOOZE_PREFERENCES, Context.MODE_PRIVATE)

  private fun storageContext(context: Context): Context =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
      context.createDeviceProtectedStorageContext()
    } else {
      context
    }

  private fun preferences(context: Context) =
    storageContext(context).getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

  private fun ringtoneDirectory(context: Context) =
    File(storageContext(context).filesDir, RINGTONE_DIRECTORY).apply { mkdirs() }

  // Copies are named by a hash of the source, so alarms sharing a melody share one copy and an
  // unchanged melody isn't copied again. Wake's own melody files are never modified in place.
  private fun copyRingtone(context: Context, uri: String): String? {
    if (uri.isEmpty()) return null
    val name =
      MessageDigest.getInstance("SHA-256").digest(uri.toByteArray()).joinToString("") {
        "%02x".format(it)
      }
    val destination = File(ringtoneDirectory(context), name)
    if (destination.length() > 0) return destination.absolutePath
    return try {
      val input =
        if (uri.startsWith("/")) {
          FileInputStream(File(uri))
        } else {
          context.contentResolver.openInputStream(Uri.parse(uri))
        } ?: return null
      val partial = File(destination.path + ".partial")
      input.use { source -> partial.outputStream().use { source.copyTo(it) } }
      partial.renameTo(destination)
      destination.absolutePath
    } catch (e: Exception) {
      Log.e(TAG, "Couldn't copy $uri", e)
      null
    }
  }
}
