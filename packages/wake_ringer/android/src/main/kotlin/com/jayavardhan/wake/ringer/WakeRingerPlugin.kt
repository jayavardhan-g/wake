package com.jayavardhan.wake.ringer

import android.content.Context
import android.util.Log
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

/** Method channel for [Ringer]; see lib/wake_ringer.dart for the Dart side. */
class WakeRingerPlugin : FlutterPlugin, MethodChannel.MethodCallHandler {
  private lateinit var channel: MethodChannel
  private lateinit var context: Context

  override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
    context = binding.applicationContext
    channel = MethodChannel(binding.binaryMessenger, "com.jayavardhan.wake/ringer")
    channel.setMethodCallHandler(this)
  }

  override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
    channel.setMethodCallHandler(null)
  }

  override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
    when (call.method) {
      "start" -> result.success(start(call))
      "setVolume" -> {
        Ringer.setVolume((call.argument<Double>("volume") ?: 1.0).toFloat())
        result.success(null)
      }
      "stop" -> {
        Ringer.stop(context)
        RingService.stop()
        result.success(null)
      }
      else -> result.notImplemented()
    }
  }

  // The sound starts first and the foreground service only once it plays, so a refused or
  // failing service can never keep an alarm from ringing.
  private fun start(call: MethodCall): Boolean {
    val ringing =
      Ringer.start(
        context,
        uri = call.argument<String>("uri") ?: "",
        usage = call.argument<Int>("usage") ?: 4,
        volume = (call.argument<Double>("volume") ?: 1.0).toFloat(),
        secondsToMaxVolume = call.argument<Int>("secondsToMaxVolume") ?: 0,
        vibrate = call.argument<Boolean>("vibrate") ?: false,
        startAtRandomPosition = call.argument<Boolean>("startAtRandomPosition") ?: false,
      )
    if (!ringing) return false
    try {
      RingService.start(context)
    } catch (e: Exception) {
      // Not allowed to start a foreground service now; keep ringing without it.
      Log.e("WakeRinger", "Couldn't start the ringing service", e)
    }
    return true
  }
}
