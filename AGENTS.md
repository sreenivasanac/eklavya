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

## Decision models

- `./gradlew :agent:eval` asks each configured model for the next step on the recorded screens. It reads
  `TYPESAFE_API_KEY`, `OPENAI_API_KEY`, `XAI_API_KEY` (plus `EVAL_XAI_MODEL`) and `EVAL_LOCAL_URL` /
  `EVAL_LOCAL_MODEL` from the environment. The owner's keys are in `~/tasks/fitness_coach_app_keys.txt`
  (a name line, then a value line); load them into the environment without printing them.
- A full eval run costs about $0.001 on Jev, $0.006 on gpt-5-nano and $0.02 on Grok 4.20.
- On the phone: `set jev_key=...` once, then `nav <exercise>`. When a mistake shows up on the phone, dump
  that screen, add it as a fixture and an eval case, and fix it there first.
- Prefer a rule in `Navigator` over another sentence in `AppNotes` when the model cannot know the answer
  from the screen (which exercise a playing video shows, for one).

## Voice

- A session can only be started from the app's screen (Android allows the microphone to start only then).
  For a test: open the app, tap Start session, then `hear <file>` plays a 16 kHz mono PCM16 file from the
  app's files folder into the session as if spoken (copy it in with `adb push` to `/data/local/tmp` and
  `adb shell run-as com.pragnyalabs.eklavya cp ...`). The log shows the wake word, both transcripts, the
  tool call and the billed seconds.
- An open voice session costs $0.05 a minute whether or not anyone speaks. A test script that talks to
  GPT-Live must have a hard time limit; the server sends events continuously, so "wait until the socket
  is quiet" never ends.
- GPT-Live's output audio stream runs on through silences: use the transcript events, not audio, to tell
  when the coach has finished.
- End the session (the app's button) and restore stay-awake when testing is done; the microphone service
  keeps running otherwise.
- UI changes follow `DESIGN.md`.

## Known gaps

- Two guards keep the navigator away from commitments: an English keyword list on button labels
  (`Navigator.RISKY`) and a model-judged question asked with every decision (`Decider.COMMITMENT`).
  The second has been tested only on one purchase dialog built in code, not on a real one.
- The voice path has been tested only with synthesized recordings played into the session: not with a
  person speaking, earbuds, Bluetooth, or gym noise.
- On screens it finds ambiguous Jev answers with low confidence and the navigator stops rather than acts.

- `TwentyFourGo.playDemo` reports success when the player page is on screen. 24GO resumes a demo where it was
  left, so one watched to the end opens finished and paused; the route does not restart it yet.
- `TwentyFourGo.findDemo` takes the first matching result on the current page; an exact match further down loses.
- Other apps' accessibility overlays and system windows are not treated as covers (`ScreenReader.read`).
