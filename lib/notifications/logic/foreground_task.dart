import 'package:flutter_foreground_task/flutter_foreground_task.dart';

void initForegroundTask() {
  FlutterForegroundTask.init(
    androidNotificationOptions: AndroidNotificationOptions(
      channelId: 'foreground_service',
      channelName: 'Foreground Service Notification',
      channelDescription:
          'This notification appears when the foreground service is running.',
      channelImportance: NotificationChannelImportance.LOW,
      priority: NotificationPriority.LOW,
      iconData: const NotificationIconData(
        resType: ResourceType.drawable,
        resPrefix: ResourcePrefix.ic,
        name: 'alarm_icon',
      ),
      // buttons: [
      //   const NotificationButton(id: 'sendButton', text: 'Send'),
      //   const NotificationButton(id: 'testButton', text: 'Test'),
      // ],
    ),
    iosNotificationOptions: const IOSNotificationOptions(
      showNotification: true,
      playSound: false,
    ),
    foregroundTaskOptions: const ForegroundTaskOptions(
      // The service only keeps the process alive; alarms wake the device
      // through AlarmManager.setAlarmClock. Holding a wake lock here kept the
      // CPU out of deep sleep for as long as the notification was enabled.
      interval: 1000 * 60,
      isOnceEvent: true,
      autoRunOnBoot: true,
      allowWakeLock: false,
      allowWifiLock: false,
    ),
  );
}
