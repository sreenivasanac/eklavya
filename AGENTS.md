# Working on Eklavya

Decisions and findings are in `docs/REQUIREMENTS.md`; read it first.

## Build on the owner's Mac

Homebrew's Gradle runs on JDK 27 by default, which the Android plugin does not accept. Set both before any Gradle command:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
```

- `./gradlew :screen:test` runs the JVM tests (real 24GO screens under `screen/src/test/resources/24go/`).
- `./gradlew :app:assembleDebug` then `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

## Testing on the phone (Pixel 7a, Android 16)

- The phone locks after 30 seconds and nothing can unlock it for you: ask the owner to unlock, then
  `adb shell svc power stayon true` (the Mac counts as wall power, so `stayon usb` does nothing).
  Put it back with `adb shell settings put global stay_on_while_plugged_in 0` when done.
- Before any `adb shell input tap`, check that the target app is in front
  (`adb shell dumpsys window | grep mCurrentFocus`); the owner may be using the phone.
- The service is driven with the debug broadcast in `DebugCommands.kt`; read results with `adb logcat -s Eklavya`.
  An argument with spaces must be quoted inside the device shell: `adb shell "am broadcast ... --es arg 'front squat'"`.
- New fixtures: `adb shell uiautomator dump`, then replace names and places before committing.

## Known gaps

- `TwentyFourGo.findDemo` takes the first matching result on the current page; an exact match further down loses.
- Other apps' accessibility overlays and system windows are not treated as covers (`ScreenReader.read`).
