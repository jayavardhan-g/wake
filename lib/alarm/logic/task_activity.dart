import 'package:flutter/foundation.dart';

/// Lets alarm tasks tell the alarm screen the user is working on them, for
/// input the screen can't see as touches: typing on the on-screen keyboard.
/// The screen keeps the alarm quieter only while the user is active.
class TaskActivity extends ChangeNotifier {
  void report() => notifyListeners();
}

final taskActivity = TaskActivity();
