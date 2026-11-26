# Requirements and decisions

Decisions taken with the owner on 2026-11-25. Change them here when they change.

## Product

- A voice coach used through earphones with the phone on a tripod at the gym.
- For the owner alone at first (24 Hour Fitness, the 24GO app); sideloaded.
- Phase 1: talk to the coach, plan the workout, log sets by voice, and
  control 24GO's screens (find an exercise, play its video).
- Phase 2: front-camera rep counting and form correction.
- The coach speaks on its own at set moments (rest over, next exercise) and
  otherwise waits for a wake word.
- 24GO stays in front; the coach is a floating overlay.

## Technical

- Android only, native Kotlin. Test phone: Pixel 7a, Android 16.
- Other apps are driven through an accessibility service (this repo's
  `a11y/` and `screen/` modules), to be published as a library.
- Voice: speech-to-speech (OpenAI GPT-Live), opened per exchange.
- Decisions: TypeSafe Jev chooses the tool and each navigation step; the
  voice model only talks and listens.
- Workout history lives in Hevy (its API creates a finished workout, so sets
  are journaled on the phone and posted when the session ends).
- The exercise list is read from 24GO.
- Model access: the owner's own API keys. Budget: up to $1 per one-hour session,
  which rules out leaving a voice session open for the whole hour.

## Findings

- 24GO (1.94.0, package `io.a24go.android.dev`) exposes labelled elements;
  nearly every tappable element has text, a description or a view id.
- 24GO search runs only when the keyboard's search key is pressed, and needs
  24GO's own exercise names ("lat pulldown" finds nothing, "squat" does).
- A demo page carries the form cues as plain text ("What to expect").
- A full-screen layer inside the same window (the open search) leaves the
  screen under it in the tree; drawing order is needed to leave it out.
- The phone locks after 30 seconds; the app must keep the screen on during
  a session.

## Models and providers (decided 2026-11-25)

Cloud only: the owner dropped on-device models, since 24GO's pages and videos need a connection anyway.

- **Decisions: TypeSafe Jev.** On the 16-case test set it was right as often as the best alternative and
  about ten times faster and twenty-five times cheaper. Any OpenAI-style chat endpoint (OpenAI, xAI,
  OpenRouter) is the configurable alternative (`decider=chat`). Risk: one small vendor.
- **Voice: OpenAI GPT-Live 1.1** over a WebSocket, one session per exchange, with a small text model
  (`gpt-5.6-luna`) choosing the tool ("Responses delegation"). Research put it first on tool tasks and
  second in heavy noise. Runner-up: Gemini Live (about a quarter of the price, weaker on tool tasks);
  third: Grok Voice (fastest, weakest in noise, $0.08 a minute). No aggregator serves realtime voice
  with one hosted key, so the app takes per-provider keys.
- A WebSocket session has no cheaper standby rate (that is WebRTC only), which is one more reason to
  close the session after each exchange.
- **Wake word: "Hey Drona"**, spotted on the phone by sherpa-onnx's open-vocabulary model. "Hey Eklavya"
  is a poor fit for an untrained model (0 of 16 test clips caught). A trained model (LiveKit WakeWord,
  the best noise numbers found) would allow any phrase but needs LiveKit Cloud keys and a training run.
- On the phone's own speaker the microphone is muted towards the model while the coach speaks, so it
  does not hear itself; with earphones it listens throughout.

## Open

- 24GO's terms on automation have not been read.
- Voice is untested with a real voice, with earbuds and in a gym. With recordings: about 12 s from the wake phrase to the video.
- The coach has three tools (show a demo, count a set, report on the last set). Planning, set logging
  (Hevy) and the 24GO exercise catalogue are next.
- Camera counting has not seen a live person; see "Camera" below for what was and was not tested.
- Hevy has not been driven or called.

## Camera: rep counting and form (decided and built 2026-11-26)

- **Pose on the phone.** MediaPipe Pose Landmarker, "full" model, through `tasks-vision` 1.2.2 on the graphics
  chip with a fall-back to the processor. Measured on the Pixel 7a: about 37 ms a frame, about 21 frames a
  second from the front camera with another app in front. No picture is stored or sent.
- **The exercise is told, not recognised.** "Count my squats" names it; recognising it from the picture would
  add a model and a way to be wrong for nothing.
- **One number per exercise, and its turning points.** Squat: hip drop as a share of standing hip height.
  Curl: elbow bend in degrees. Press: wrist height above the shoulders in torso lengths. A rep is a rise of
  at least a minimum size followed by a fall of 60% of that rise, or of a usual rep when the rise was larger
  (so the first rep after picking up the weights counts). Fixed "rest" and "bottom" levels were
  tried first and dropped: picking up the dumbbells moved the rest level and the count stopped.
- **Form rules are geometry, with limits set from recordings.** Each fault is one inequality on joint
  positions at the rep's far point or across the rep (the list is in the README). A rule runs only from the
  view that can see it and when its joints are clearly visible.
- **Cues are rationed.** Two reps running before anything is said, one cue per three reps, the same cue not
  again for six, and one line after the set. The wording is fixed in code.
- **Counts are spoken by the phone's own speech engine**, so nothing is billed during a set, and the voice
  exchange closes four seconds after counting starts. The cost of that: two different voices.
- **Sets end by themselves**: eight seconds after the last rep; three seconds after the user leaves the
  picture, once a rep has been counted; or after a minute with no rep. Before the first rep, leaving the
  picture does not end the set: people step out to fetch a weight.

Tried and not chosen: sending frames to a vision model in the cloud (cost per set, delay on every rep, and the
gym's network); a learned rep counter (nothing to train it on, and it cannot say why it counted).

Not built yet, in the order they seem worth doing:

1. Try it with a person, and adjust the limits to real sets (the recordings are of four people).
2. Take the cue wording from the exercise's own form notes in 24GO, chosen by the decision model.
3. Push-ups and lunges; a barbell hiding the wrists or hips.
4. Write counted sets to Hevy.
