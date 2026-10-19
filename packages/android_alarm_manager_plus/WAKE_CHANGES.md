# Wake changes to android_alarm_manager_plus

Vendored from [AhsanSarwar45/plus_plugins](https://github.com/AhsanSarwar45/plus_plugins)
branch `alarm_show_intent` at `ae6c11c` (the version Chrono 0.6.0 used). `example/` and `test/`
were dropped.

- `AlarmBroadcastReceiver` runs the alarm's Dart callback directly (`AlarmService.handleAlarmNow`)
  instead of going through `JobIntentService.enqueueWork`. On Android 8+ that uses JobScheduler,
  which defers jobs while the device is in Doze, so alarms on a locked, idle phone only rang
  after it was unlocked. The receiver holds a 60 s self-releasing wake lock while the engine and
  ringtone start, and `goAsync()` keeps it alive for up to 9 s.
- `AlarmService` (the JobIntentService) is still used for anything else that enqueues work.
