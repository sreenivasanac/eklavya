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

## The website and CodeRabbit Verify

- The only web app in this repository is the static site in `site/`. It needs no install and no build:
  `python3 -m http.server 8080 --directory site`, then open `http://localhost:8080/`. The Android app cannot
  run in a sandbox without a phone, and CodeRabbit Verify does not test native apps: for Verify, the thing to
  start and test is the site.
- Verify state on 2026-11-26: the repository is added at app.coderabbit.ai → Verify, its environment
  ("eklavya") serves the site, and test instructions are saved. Three local runs
  (`coderabbit verify --committed --base-commit <commit> --dir site --agent`) were admitted and then failed
  while generating scenarios with "Verify could not complete because its workflow failed" and no detail.
  Verify's own page still says "Environment setup did not complete. Retry from Setup" and uses snapshot
  revision 1, though the environment page shows revision 2 prepared. Retrying setup starts CodeRabbit's
  agent again (it spent about nine agent-minutes the first time): ask the owner before doing that.
- Deploy: `rsync -az --delete --exclude DESIGN.md --exclude og.html --exclude .DS_Store site/ hetzner-agent:/var/www/eklavya/`,
  then open https://eklavya.pragnyalabs.com/ and check both pages. No analytics on this site, on purpose.
- When the app's behaviour or measurements change, the README and the site say the same things: update both.

## Camera and rep counting

- The counter and form rules are plain Kotlin in `motion/` and are tuned on recorded poses, not on the phone:
  `./gradlew :motion:replay -Pposes=<folder>` prints the count and cues for every recording in a folder.
  A recording is made from a video with `tools/pose_fixture.py` (MediaPipe in Python, the pose model in
  `app/src/main/assets`; `uv run --python 3.12 --with mediapipe --with opencv-python-headless python tools/pose_fixture.py MODEL CLIPS OUT`), one
  line per frame: `t_ms` then 33 `x y visibility` triples, x multiplied by width/height. Restart tracking
  after five frames with the shoulders below the hips, as `SetWatcher` does, or the recording will not match
  what the phone sees.
- On the phone, `watchfile 'squat=clip.mp4'` runs a video from the app's files folder through the phone's pose
  model and the tracker (a session must be running). Re-encode the clip first with every frame a key frame
  (`ffmpeg -i in.mp4 -vf scale=-2:960 -g 1 -an out.mp4`), or reading it takes minutes. `watch squat` uses the
  camera and needs the camera permission granted before the session starts.
- The phone speaks counts aloud, and the coach's voice and 24GO's videos play aloud too. Before a test while
  the owner is asleep or in a meeting: `adb shell cmd audio adj-mute 3` (media) and note then zero the
  assistant stream (`adb shell cmd audio get-stream-volume 11`, `adb shell cmd audio set-volume 11 0`).
  Check with `adb shell cmd audio is-stream-mute 3` and `get-stream-volume 11`: on 2026-11-26
  `cmd media_session volume --set 0` printed as if it worked and changed nothing, and a night of tests was
  audible. Afterwards `adj-unmute 3` and set stream 11 back.
- `tools/squat_chart.py` draws the README's and the site's squat-depth chart from a recording. It carries a
  copy of the counter and fails if its count differs from the one given, so redraw it when the counter changes.
- The thresholds in `SetTracker` and `Exercise` are tuning values from 32 recorded sets of four people. When
  a real set is miscounted or gets a wrong cue, record it, add it under `motion/src/test/resources/poses/`
  with the right answer, and change the value there.
- A word said straight after the wake phrase is only heard because the last 2.5 seconds of audio are sent
  with it (`CoachService.PRE_ROLL_FRAMES`). Test new requests with clips that do not pause after "Hey Drona".

## Known gaps

- The camera path has never seen a live person: only recorded sets (as poses on a laptop, and three videos
  through the phone's pose model) and an empty room through the phone's camera.
- The coach's voice and the phone's counting voice are two different voices.
- Only squats, bicep curls and shoulder presses are counted. Form limits are the same for every body and
  every camera angle; a phone that looks up or down at the user shifts the squat-depth reading.

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
