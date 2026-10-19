import 'dart:isolate';
import 'dart:ui';

import 'package:clock_app/alarm/logic/alarm_isolate.dart';
import 'package:clock_app/common/types/notification_type.dart';
import 'package:clock_app/notifications/logic/alarm_notifications.dart';
import 'package:clock_app/developer/logic/logger.dart';
import 'package:clock_app/settings/types/listener_manager.dart';

void initializeIsolatePorts(){
    ReceivePort receivePort = ReceivePort();
  IsolateNameServer.removePortNameMapping(updatePortName);
  IsolateNameServer.registerPortWithName(receivePort.sendPort, updatePortName);
  printIsolateInfo();
  receivePort.listen((message) {
    if (message == "updateAlarms") {
      ListenerManager.notifyListeners("alarms");
    } else if (message == "updateTimers") {
      ListenerManager.notifyListeners("timers");
    } else if (message == "updateStopwatches") {
      ListenerManager.notifyListeners("stopwatch");
    } else if (message == closeAlarmScreenMessage) {
      // The alarm isolate stopped a ringing alarm on its own (ring duration
      // ran out), so close the alarm screen if it's showing.
      closeAlarmNotification(ScheduledNotificationType.alarm);
    }
  });
}
