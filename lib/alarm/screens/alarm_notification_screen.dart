import 'dart:async';
import 'dart:ui';

import 'package:clock_app/alarm/logic/alarm_isolate.dart';
import 'package:clock_app/alarm/logic/task_activity.dart';
import 'package:clock_app/alarm/utils/alarm_id.dart';
import 'package:clock_app/alarm/types/alarm.dart';
import 'package:clock_app/common/types/notification_type.dart';
import 'package:clock_app/common/widgets/clock/digital_clock.dart';
import 'package:clock_app/developer/logic/logger.dart';
import 'package:clock_app/navigation/types/routes.dart';
import 'package:clock_app/notifications/logic/alarm_notifications.dart';
import 'package:clock_app/notifications/types/alarm_notification_arguments.dart';
import 'package:clock_app/navigation/types/alignment.dart';
import 'package:clock_app/notifications/widgets/notification_actions/slide_notification_action.dart';
import 'package:clock_app/settings/data/settings_schema.dart';
import 'package:flutter/material.dart';

class AlarmNotificationScreen extends StatefulWidget {
  const AlarmNotificationScreen({
    super.key,
    required this.scheduleId,
    this.onPop,
    this.initialIndex = -1,
    this.dismissType = AlarmDismissType.dismiss,
  });

  final int scheduleId;
  final int initialIndex;
  final Function? onPop;
  final AlarmDismissType dismissType;

  @override
  State<AlarmNotificationScreen> createState() =>
      _AlarmNotificationScreenState();
}

class _AlarmNotificationScreenState extends State<AlarmNotificationScreen> {
  // While a task is shown the alarm is quieter. With no input for this long it
  // goes back to full volume, so dozing off on a task doesn't leave a quiet
  // alarm; the next input lowers it again. The task's progress is kept.
  static const _taskIdleTimeout = Duration(seconds: 15);

  late Alarm alarm;
  late Widget _currentWidget;
  late int _currentIndex = widget.initialIndex;
  late Widget actionWidget;
  Timer? _taskIdleTimer;
  bool _onTask = false;
  bool _raisedForIdle = false;

  // A factor of the alarm's own volume. Sending an absolute volume (as this
  // screen used to) cancelled rising volume as soon as the screen opened.
  void _sendVolumeFactor(double factor) {
    IsolateNameServer.lookupPortByName(setAlarmVolumePortName)?.send([factor]);
  }

  void _onTaskActivity() {
    if (!_onTask) return;
    if (_raisedForIdle) {
      _raisedForIdle = false;
      _sendVolumeFactor(alarm.volumeDuringTasks / 100);
    }
    _taskIdleTimer?.cancel();
    _taskIdleTimer = Timer(_taskIdleTimeout, () {
      _raisedForIdle = true;
      _sendVolumeFactor(1.0);
    });
  }

  void _leaveTasks() {
    _onTask = false;
    _taskIdleTimer?.cancel();
  }

  void _setNextWidget() {
    setState(() {
      if (_currentIndex < 0) {
        _leaveTasks();
        _sendVolumeFactor(1.0);
        _currentWidget = actionWidget;
      } else if (_currentIndex >= alarm.tasks.length) {
        _leaveTasks();
        if (widget.onPop != null) {
          widget.onPop!();
          Navigator.of(context).pop(true);
        } else {
          dismissAlarmNotification(widget.scheduleId, widget.dismissType,
              ScheduledNotificationType.alarm);
        }
      } else {
        _onTask = true;
        _raisedForIdle = false;
        _sendVolumeFactor(alarm.volumeDuringTasks / 100);
        _onTaskActivity();
        _currentWidget = alarm.tasks[_currentIndex].builder(_setNextWidget);
      }
      _currentIndex++;
    });
  }

  @override
  void initState() {
    super.initState();
    taskActivity.addListener(_onTaskActivity);

    Alarm? currentAlarm = getAlarmById(widget.scheduleId);
    if (currentAlarm == null) {
      dismissAlarmNotification(widget.scheduleId, widget.dismissType,
          ScheduledNotificationType.alarm);
      return;
    }
    alarm = currentAlarm;

    try {
      actionWidget = appSettings
          .getGroup("Alarm")
          .getSetting("Dismiss Action Type")
          .value
          .builder(_setNextWidget, alarm.canBeSnoozed ? _snoozeAlarm : null,
              "Dismiss", "Snooze");
    } catch (e) {
      actionWidget = SlideNotificationAction(
        onDismiss: _setNextWidget,
        onSnooze: alarm.canBeSnoozed ? _snoozeAlarm : null,
        dismissLabel: "Dismiss",
        snoozeLabel: "Snooze",
      );

      logger.e(e.toString());
    }

    _setNextWidget();
  }

  @override
  void dispose() {
    taskActivity.removeListener(_onTaskActivity);
    _taskIdleTimer?.cancel();
    super.dispose();
  }

  void _snoozeAlarm() {
    dismissAlarmNotification(widget.scheduleId, AlarmDismissType.snooze,
        ScheduledNotificationType.alarm);
  }

  @override
  Widget build(BuildContext context) {
    return WillPopScope(
      onWillPop: () async {
        Routes.pop(onlyUpdateRoute: true);
        return true;
      },
      child: Scaffold(
        // Touches count as working on a task; typing is reported by the
        // tasks through taskActivity.
        body: Listener(
          behavior: HitTestBehavior.translucent,
          onPointerDown: (_) => _onTaskActivity(),
          child: SingleChildScrollView(
          child: SizedBox(
            height: MediaQuery.of(context).size.height,
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              crossAxisAlignment: CrossAxisAlignment.center,
              children: [
                if (_currentIndex <= 0)
                  Expanded(
                    flex: 2,
                    child: Padding(
                      padding: const EdgeInsets.symmetric(horizontal: 20.0),
                      child: Column(
                        children: [
                          const Spacer(),
                          if (alarm.label.isNotEmpty)
                            Text(
                              alarm.label,
                              style: Theme.of(context).textTheme.displayMedium,
                              textAlign: TextAlign.center,
                              maxLines: 3,
                              overflow: TextOverflow.ellipsis,
                            ),
                          const SizedBox(height: 8),
                          const DigitalClock(
                            // dateTime: Date,
                            horizontalAlignment: ElementAlignment.center,
                            shouldShowDate: false,
                            shouldShowSeconds: false,
                          ),
                          const SizedBox(height: 8),
                          Text(
                            "Alarm",
                            style: Theme.of(context).textTheme.headlineMedium,
                          ),
                        ],
                      ),
                    ),
                  ),
                Expanded(
                  flex: 2,
                  child: Column(
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: [
                      _currentWidget,
                    ],
                  ),
                ),
                // if (!alarm.maxSnoozeIsReached)
                //   TextButton(
                //     onPressed: _snoozeAlarm,
                //     child: const Text("Snooze"),
                //   ),
              ],
            ),
          ),
        ),
        ),
      ),
    );
  }
}

class NotificationUtils {}
