<div align="center">

<image src="icon.png" height="100"/>

# Wake

### A fork of [Chrono](https://github.com/vicolo-dev/chrono) focused on alarms that always ring

[![Latest release](https://img.shields.io/github/v/release/jayavardhan-g/wake)](https://github.com/jayavardhan-g/wake/releases/latest)
![tests](https://github.com/jayavardhan-g/wake/actions/workflows/tests.yml/badge.svg)

</div>

Wake is [Chrono](https://github.com/vicolo-dev/chrono) 0.6.0 (clock, alarms, timer and
stopwatch for Android) with fixes for alarms that rang silently, late, or not at all, and a
native ringer that behaves like the stock clock app. Everything else is Chrono's app and work.
It is a personal fork, tested mainly on a Motorola Edge 60 Pro running Android 17.

It installs next to Chrono (package `com.jayavardhan.wake`); Chrono's backup and restore moves
your alarms over.

## What's different from Chrono

**Alarms ring reliably**
- Alarms are rung natively from a foreground service, so the sound always plays: on recent
  Android versions Chrono's player silently gave up when another app held audio, leaving only
  vibration.
- Music and video pause while an alarm rings and resume afterwards.
- Alarms ring on time on an idle, locked phone. Chrono queued the ringing as a background job,
  which Android postpones until the phone is unlocked.
- After a reboot, alarms ring before the phone is unlocked, with Snooze and Dismiss on the
  lock screen. Snoozes survive reboots; an alarm missed while the phone was off is reported
  as missed.

**Alarm behaviour**
- An alarm left ringing snoozes itself after a set ring duration ("Ring Duration", default 5
  minutes), up to Max Snoozes, then shows a missed-alarm notification.
- Rising volume works, including alongside alarm tasks.
- While solving an alarm task the alarm is quieter, but it goes back to full volume after 15
  seconds without input.
- A snoozed one-time alarm no longer rings again the next day.

**Other**
- If a melody can't be played, the phone's default alarm sound, then a bundled one, plays
  instead of nothing.
- Importing a melody you already have doesn't store a second copy.
- The optional persistent notification no longer keeps the phone out of deep sleep.

## Install

Download the APK for your device (`arm64-v8a` for most phones) from
[Releases](https://github.com/jayavardhan-g/wake/releases/latest), or add this repository to
[Obtainium](https://github.com/ImranR98/Obtainium) to get updates.

For alarms to sound while Do Not Disturb is on, allow alarms in your Do Not Disturb settings.

## Features

Inherited from Chrono:

- Modern and easy to use interface, available in a variety of languages
### Alarms
- Customizable schedules (Daily, Weekly, Specific week days, Specific dates, Date range)
- Configure melody/ringtone, rising volume and vibrations
- Configure Snooze length, max snoozes and other snooze behaviour
- Option to auto delete dismissed alarms and skip alarms
- Alarm tasks (Math problems, Retype text, Sequence)
- Dial, spinner and text time pickers
- Filter and sort alarms, add tags
### Clock
- Customizable clock display
- World clocks with relative time difference
### Timer
- Multiple timers, presets, fullscreen timer
- Configure melody/ringtone, rising volume and vibrations
### Stopwatch
- Lap history with lap times, elapsed times and comparisons
### Appearance
- Material You icons and themes, customizable color and style themes

## Development

The app is built with Flutter (3.22). To build locally, install Flutter and run
`flutter run --flavor dev`, or `flutter build apk --release --split-per-abi --flavor prod`.

Releases are built by GitHub Actions: pushing a `v*` tag builds signed split APKs and publishes
a release. The **Tests** workflow (`flutter analyze` and `flutter test`) runs on every push.

Wake's native code lives in two local packages:
- `packages/wake_ringer`: the native ringer, its foreground service and alarms rung before the
  first unlock after a reboot.
- `packages/android_alarm_manager_plus`: a vendored copy of the alarm plugin Chrono uses, changed
  as described in its `WAKE_CHANGES.md`.

## Screenshots

From Chrono; Wake looks the same.

<p float="left">
<image src="fastlane/metadata/android/en-US/images/phoneScreenshots/1.png" height="400"/>
<image src="fastlane/metadata/android/en-US/images/phoneScreenshots/2.png" height="400"/>
<image src="fastlane/metadata/android/en-US/images/phoneScreenshots/3.png" height="400"/>
<image src="fastlane/metadata/android/en-US/images/phoneScreenshots/4.png" height="400"/>
<image src="fastlane/metadata/android/en-US/images/phoneScreenshots/5.png" height="400"/>
</p>

## Credits and license

Wake is a modified version of [Chrono](https://github.com/vicolo-dev/chrono) by
[vicolo-dev](https://github.com/vicolo-dev) and its contributors. If you find it useful,
consider [supporting Chrono's developer](https://patreon.com/vicolo).

Modified by [jayavardhan-g](https://github.com/jayavardhan-g) from October 2026; the changes are
listed above and in the commit history. Like Chrono, Wake is licensed under the
[GNU General Public License v3.0](LICENSE).
